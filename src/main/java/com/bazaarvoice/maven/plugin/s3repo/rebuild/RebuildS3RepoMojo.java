package com.bazaarvoice.maven.plugin.s3repo.rebuild;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.bazaarvoice.maven.plugin.s3repo.S3RepositoryPath;
import com.bazaarvoice.maven.plugin.s3repo.WellKnowns;
import com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraFileUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraIOUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.S3Utils;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.io.InputStreamFacade;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Mojo (name = "rebuild-repo", requiresProject = false)
public final class RebuildS3RepoMojo extends AbstractMojo {

    /** Staging directory. This is where we will recreate the relevant *bucket* files (i.e., this acts as the
       root of the repository). */
    @Parameter(property = "s3repo.stagingDirectory")
    private File stagingDirectory;

    /**
     * The s3 path to the root of the source (and possibly target) repository.
     * These are all valid values:
     *      "s3://Bucket1/Repo1"
     *      "/Bucket/Repo1"
     */
    @Parameter (property = "s3repo.repositoryPath", required = true)
    private String s3RepositoryPath;

    /**
     * Optional target repository path. Otherwise the {@link #s3RepositoryPath} is used.
     */
    @Parameter (property = "s3repo.targetRepositoryPath", required = false)
    private String s3TargetRepositoryPath;

    /** Whether or not this goal should be allowed to create a new repository if it's needed. */
    @Parameter(property = "s3repo.allowCreateRepository", defaultValue = "false")
    private boolean allowCreateRepository;

    @Parameter(property = "s3repo.accessKey")
    private String s3AccessKey;

    @Parameter(property = "s3repo.secretKey")
    private String s3SecretKey;

    @Parameter(property = "s3repo.sessionToken")
    private String s3SessionToken;

    @Parameter(property = "s3repo.awsStsFlag", defaultValue = "false")
    private boolean awsStsFlag;

    /** Do not try to validate the current repository metadata before recreating the repository. */
    @Parameter(property = "s3repo.doNotValidate", defaultValue = "false")
    private boolean doNotValidate;

    @Parameter(property = "s3repo.removeOldSnapshots", defaultValue = "false")
    private boolean removeOldSnapshots;

    @Parameter(property = "s3repo.removeOldRepodata", defaultValue = "false")
    private boolean removeOldRepodata;

    /** Execute all steps up to and excluding the upload to the S3. This can be set to true to perform a "dryRun" execution. */
    @Parameter(property = "s3repo.doNotUpload", defaultValue = "false")
    private boolean doNotUpload;

    /** Only upload the new repo metadata. BUT we will ALWAYS upload files from the source repository if the source and
     * target repositories are different. */
    @Parameter(property = "s3repo.uploadMetadataOnly", defaultValue = "true")
    private boolean uploadMetadataOnly;

    /** Indicates whether we should clean the staging directory before pulling the repository; this is helpful because
      existing files in staging are not re-downloaded; this is especially helpful for debugging this plugin during
      development. */
    @Parameter(property = "s3repo.doNotPreClean", defaultValue = "false")
    private boolean doNotPreClean;

    /** The createrepo executable. */
    @Parameter(property = "s3repo.createrepo", defaultValue = "createrepo")
    private String createrepo;

    /** Comma-delimited **repo-relative** paths to exclude when rebuilding. Example value:
     *      path/to/awesome-artifact-1.4-SNAPSHOT1.noarch.rpm,path/to/awesome-artifact-1.4.noarch.rpm
     */
    @Parameter(property = "s3repo.excludes", defaultValue = "")
    private String excludes;

    /** Additional options for the createrepo command. See http://linux.die.net/man/8/createrepo. */
    @Parameter(property = "s3repo.createrepoOpts", defaultValue = "")
    private String createrepoOpts;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        determineAndSetStagingDirectoryIfNeeded();
        determineAndSetTargetRepositoryPathIfNeeded();

        RebuildContext context = new RebuildContext();

        context.setS3Session(createS3Client());
        context.setS3RepositoryPath(parseS3RepositoryPath(s3RepositoryPath));
        context.setS3TargetRepositoryPath(parseS3RepositoryPath(s3TargetRepositoryPath));
        context.setLocalYumRepo(determineLocalYumRepo());
        context.setExcludedFiles(parseExcludedFiles());

        logRepositories(context);

        // always clean staging directory
        maybeCleanStagingDirectory();

        // download source (and target, if needed) repositories
        downloadRepositories(context);
        // perform some checks to ensure repository is as expected if doNotValidate = false
        maybeValidateRepository(context);
        // remove old snapshots if removeOldSnapshots = true
        maybeRemoveOldSnapshots(context);
        // we don't download excluded files but they may already exist if doNotPreClean = true
        deleteExcludes(context);
        // rebuild -- rerun createrepo
        rebuildRepo(context);
        // upload repository and delete old snapshots etc. if doNotUpload = false
        maybeUploadRepository(context);
    }

    private void logRepositories(RebuildContext context) {
        getLog().info("For source repository, using " + context.getS3RepositoryPath() + ".");
        if (context.sourceAndTargetRepositoryAreSame()) {
            getLog().info("Target repository and source repository are the SAME.");
        } else {
            getLog().info("For target repository, using " + context.getS3TargetRepositoryPath() + ".");
        }
    }

    private void determineAndSetTargetRepositoryPathIfNeeded() {
        if (StringUtils.isBlank(s3TargetRepositoryPath)) {
            s3TargetRepositoryPath = s3RepositoryPath;
        }
    }

    private List<String> parseExcludedFiles() {
        List<String> excludedFiles = Lists.newArrayList();
        if (!StringUtils.isEmpty(excludes)) {
            for (String excludedFile : excludes.split(",")) {
                if (!StringUtils.isEmpty(excludedFile)) {
                    excludedFiles.add(excludedFile);
                }
            }
        }
        return excludedFiles;
    }

    private void determineAndSetStagingDirectoryIfNeeded() {
        if (stagingDirectory == null) {
            stagingDirectory = Files.createTempDir();
        }
        getLog().info("I will use " + stagingDirectory.getAbsolutePath() + " as your staging directory.");
    }

    private void maybeCleanStagingDirectory() throws MojoExecutionException {
        if (doNotPreClean) {
            getLog().warn("Not cleaning staging directory!!!");
            return;
        }
        ExtraFileUtils.createOrCleanDirectory(stagingDirectory);
    }

    private void maybeUploadRepository(RebuildContext context) throws MojoExecutionException {
        String logPrefix = "";
        if (doNotUpload) {
            getLog().info("Per configuration, we will NOT perform any remote operations on the S3 repository.");
            logPrefix = "SKIPPING: ";
        }
        final S3RepositoryPath targetRepository = context.getS3TargetRepositoryPath();
        final String targetBucket = targetRepository.getBucketName();
        AmazonS3 s3Session = context.getS3Session();

        // Determine directory to upload
        File directoryToUpload = uploadMetadataOnly
                ? context.getLocalYumRepo().repoDataDirectory() // only the repodata directory
                : stagingDirectory; // the entire staging directory/bucket

        // Check if repository already exists
        if (!allowCreateRepository && !context.getLocalYumRepo().isRepoDataExists()) {
            throw new MojoExecutionException("refusing to create new repo: " + targetRepository +
                " (use s3repo.allowCreateRepository = true to force)");
        }

        // Build list of YUM metadata (repodata) to delete
        List<S3ObjectSummary> s3RepoDataToDeleteList = new ArrayList<S3ObjectSummary>();
        if (removeOldRepodata && context.sourceAndTargetRepositoryAreSame()) {
            ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
                    .withBucketName(targetBucket)
                    .withPrefix(targetRepository.getBucketRelativeFolder() + "/" + WellKnowns.YUM_REPODATA_FOLDERNAME + "/");

            List<S3ObjectSummary> list = S3Utils.listAllObjects(s3Session, listObjectsRequest);
            Collection<File> localMetaDataFiles = ExtraIOUtils.listAllFiles(context.getLocalYumRepo().repoDataDirectory());
            for (S3ObjectSummary oldMetaDataObject : list) {
                if (!containsMetaDataFile(localMetaDataFiles, oldMetaDataObject.getKey())) {
                    s3RepoDataToDeleteList.add(oldMetaDataObject);
                }
            }
        }

        // Upload repository files
        for (File toUpload : ExtraIOUtils.listAllFiles(directoryToUpload)) {
            final String bucketKey = localFileToTargetS3BucketKey(toUpload, context);
            getLog().info(logPrefix + "Uploading: " + toUpload.getName() + " => s3://" + targetRepository.getBucketName() + "/" + bucketKey + "...");
            if (!doNotUpload) {
                s3Session.putObject(new PutObjectRequest(targetBucket, bucketKey, toUpload));
            }
        }

        // Upload non-local files (e.g: moving repository)
        if (uploadMetadataOnly && !context.sourceAndTargetRepositoryAreSame()) {
            // we just uploaded metadata but there are files in the source repository
            // that don't exist in the target, so we upload those here.
            for (File toUpload : ExtraIOUtils.listAllFiles(stagingDirectory)) {
                if (!context.getFilesFromTargetRepo().contains(toUpload)) {
                    // upload if it's not already in the target repo.
                    final String bucketKey = localFileToTargetS3BucketKey(toUpload, context);
                    getLog().info(logPrefix + "Uploading: " + toUpload.getName()
                        + " => s3://" + targetRepository.getBucketName() + "/" + bucketKey + "...");
                    if (!doNotUpload) {
                        s3Session.putObject(new PutObjectRequest(targetBucket, bucketKey, toUpload));
                    }
                }
            }
        }

        // delete any excluded files remotely from the TARGET only.
        for (String repoRelativePath : context.getExcludedFilesToDeleteFromTarget()) {
            final String bucketKey = toBucketKey(targetRepository, repoRelativePath);
            getLog().info(logPrefix + "Deleting: "
                + "s3://" + targetRepository.getBucketName() + "/" + bucketKey + " (excluded file)");
            if (!doNotUpload) {
                context.getS3Session().deleteObject(targetBucket, bucketKey);
            }
        }

        // and finally, delete any remote bucket keys we wish to remove (e.g., old snaphots)...from the TARGET only.
        for (SnapshotDescription toDelete : context.getSnapshotsToDeleteRemotely()) {
            getLog().info(logPrefix + "Deleting: "
                + "s3://" + targetRepository.getBucketName() + "/" + toDelete.getBucketKey() + " (excluded file)");
            getLog().info(logPrefix + "Deleting: " + toDelete + " (old snapshot)");
            if (!doNotUpload) {
                context.getS3Session().deleteObject(targetBucket, toDelete.getBucketKey());
            }
        }

        // rename any snapshots...in TARGET only.
        for (RemoteSnapshotRename toRename : context.getSnapshotsToRenameRemotely()) {
            final String sourceBucketKey = toRename.getSource().getBucketKey();
            final String targetBucketKey = toRename.getNewBucketKey();
            getLog().info(logPrefix + "Renaming: "
                + "s3://" + targetRepository.getBucketName() + "/" + sourceBucketKey
                + " => s3://" + targetRepository.getBucketName() + "/" + targetBucketKey);
            if (!doNotUpload) {
                s3Session.copyObject(targetBucket, sourceBucketKey, targetBucket, targetBucketKey);
                s3Session.deleteObject(targetBucket, sourceBucketKey);
            }
        }

        // Delete old YUM metadata
        for (S3ObjectSummary repoDataSummary : s3RepoDataToDeleteList) {
            getLog().info(logPrefix + "Deleting metadata: " + repoDataSummary.getKey());
            if (!doNotUpload) {
                s3Session.deleteObject(repoDataSummary.getBucketName(), repoDataSummary.getKey());
            }
        }
    }

    private boolean containsMetaDataFile(Collection<File> metaDataFiles, String file) {
        if (file == null) {
            return false;
        }
        for (File metaDataFile : metaDataFiles) {
            if (file.endsWith(metaDataFile.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String toBucketKey(S3RepositoryPath target, String repoRelativePath) {
        return target.hasBucketRelativeFolder()
            ? target.getBucketRelativeFolder() + "/" + repoRelativePath
            : repoRelativePath;
    }

    /** Convert local file in staging directory to bucket key (in target s3 repository). */
    private String localFileToTargetS3BucketKey(File toUpload, RebuildContext context) throws MojoExecutionException {
        String relativizedPath = ExtraIOUtils.relativize(stagingDirectory, toUpload);
        // replace *other* file separators with S3-style file separators and strip first & last separator
        relativizedPath = relativizedPath.replaceAll("\\\\", "/").replaceAll("^/", "").replaceAll("/$", "");
        return context.getS3TargetRepositoryPath().hasBucketRelativeFolder()
            ? context.getS3TargetRepositoryPath().getBucketRelativeFolder() + "/" + relativizedPath
            : relativizedPath;
    }

    private void rebuildRepo(RebuildContext context) throws MojoExecutionException {
        getLog().info("Rebuilding repo...");
        context.getLocalYumRepo().createRepo();
    }

    private void deleteExcludes(RebuildContext context) throws MojoExecutionException {
        for (String repoRelativePath : context.getExcludedFiles()) {
            final File deleteMe = new File(stagingDirectory, repoRelativePath);
            if (deleteMe.isFile()) {
                if (!doNotPreClean) {
                    // assert: an excluded file exists but we pre-cleaned.
                    // pathological: if we ever fail for this reason it means we have faulty logic in this code
                    // i.e., we pre-cleaned our staging directory but we still managed to have one of our excluded
                    // files downloaded into our local staging repo.
                    throw new IllegalStateException("unexpected file in staging repo: " + deleteMe);
                }
                if (!deleteMe.delete()) {
                    throw new MojoExecutionException("failed to delete: " + deleteMe);
                }
            }
        }
    }

    /** Delete any old snapshots locally so that later, when we rebuild the repository, these old snapshots
     * will not be included. Also add old snapshots to the context so that we can later delete them
     * <em>remotely</em>.*/
    private void maybeRemoveOldSnapshots(RebuildContext context) throws MojoExecutionException {
        if (removeOldSnapshots) {
            getLog().info("Removing old snapshots...");
            Map<String, List<SnapshotDescription>> snapshots = context.getBucketKeyPrefixToSnapshots();
            for (String snapshotKeyPrefix : snapshots.keySet()) {
                List<SnapshotDescription> snapshotsRepresentingSameInstallable = snapshots.get(snapshotKeyPrefix);
                if (snapshotsRepresentingSameInstallable.size() > 1) {
                    // if there's more than one snapshot for a given installable, then we have cleanup to do
                    Collections.sort(snapshotsRepresentingSameInstallable, new Comparator<SnapshotDescription>() {
                        @Override
                        public int compare(SnapshotDescription left, SnapshotDescription right) {
                            // IMPORTANT: this ensures that *latest/newer* artifacts are ordered first
                            return right.getOrdinal() - left.getOrdinal();
                        }
                    });
                    // start with *second* artifact; delete it and everything after it (these are the older artifacts)
                    for (int i = 1; i < snapshotsRepresentingSameInstallable.size(); ++i) {
                        SnapshotDescription toDelete = snapshotsRepresentingSameInstallable.get(i);
                        getLog().info("Deleting old snapshot '" + toDelete.getBucketKey() + "', locally...");
                        // delete object locally so createrepo step doesn't pick it up
                        deleteRepoRelativePath(S3Utils.toRepoRelativePath(toDelete.getBucketKey(), toDelete.getS3RepositoryPath()));
                        // only queue it for deletion if exists in the target repository.
                        if (toDelete.existsInRepository(context.getS3TargetRepositoryPath())) {
                            // we'll also delete the object from s3 but only after we upload the repository metadata
                            // (so we don't confuse any repo clients who are reading the current repo metadata)
                            context.addSnapshotToDelete(toDelete);
                        }
                    }
                    // rename the lastest snapshot (which is the first in our list) discarding it's SNAPSHOT numeric suffix
                    renameSnapshotLocalFileByStrippingSnapshotNumerics(context, snapshotsRepresentingSameInstallable.get(0));
                }
            }
        }
    }

    private void renameSnapshotLocalFileByStrippingSnapshotNumerics(RebuildContext context, SnapshotDescription snapshotDescription) throws MojoExecutionException {
        final File latestSnapshotFile = new File(stagingDirectory,
            S3Utils.toRepoRelativePath(snapshotDescription.getBucketKey(), snapshotDescription.getS3RepositoryPath()));
        final File renameTo = new File(latestSnapshotFile.getParent(), tryStripSnapshotNumerics(latestSnapshotFile.getName()));
        getLog().info("Renaming " + ExtraIOUtils.relativize(stagingDirectory, latestSnapshotFile)
                + " => " + renameTo.getName() /*note can't relativize non-existent file*/);
        if (latestSnapshotFile.renameTo(renameTo)) {
            // rename was successful -- also ensure that we queue up the snapshot to rename it remotely
            context.addSnapshotToRename(
                RemoteSnapshotRename.withNewBucketKey(snapshotDescription, localFileToTargetS3BucketKey(renameTo, context)));
        } else {
            getLog().warn("Failed to rename " + latestSnapshotFile.getPath() + " to " + renameTo.getPath());
        }
    }

    private void deleteRepoRelativePath(String repoRelativePath) throws MojoExecutionException {
        final File toDelete = new File(stagingDirectory, repoRelativePath);
        if (!toDelete.isFile()) {
            throw new MojoExecutionException("Cannot delete non-existent file: " + toDelete);
        }
        if (!toDelete.delete()) {
            throw new MojoExecutionException("Failed to delete file: " + toDelete);
        }
    }

    /** Ensure that at least all files listed in the <em>target</em> repository's metadata are present among
     * the repository files that we downloaded.
     */
    private void maybeValidateRepository(RebuildContext context) throws MojoExecutionException {
        if (doNotValidate) {
            return;
        }
        getLog().info("Validating downloaded repository...");
        LocalYumRepoFacade localYumRepo = context.getLocalYumRepo();
        if (!localYumRepo.isRepoDataExists()) {
            throw new MojoExecutionException("Repository does not exist!");
        }
        // list of files (repo-relative paths)
        List<String> fileList = localYumRepo.parseFileListFromRepoMetadata();
        for (String repoRelativePath : fileList) {
            if (!context.getExcludedFiles().contains(repoRelativePath)
                && !localYumRepo.hasFile(repoRelativePath)) {
                // repository metadata declared a (non-excluded) file that did not exist.
                throw new MojoExecutionException("Repository metadata declared file " + repoRelativePath + " but the file did not exist.");
            }
        }
    }

    /** Create a {@link com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade} which will allow us to query and operate on a local (on-disk) yum repository. */
    private LocalYumRepoFacade determineLocalYumRepo() {
        return new LocalYumRepoFacade(stagingDirectory, createrepo, createrepoOpts, getLog());
    }

    private AmazonS3Client createS3Client() throws MojoExecutionException {
        if (awsStsFlag) {
            try {
                if (s3AccessKey == null) {
                    s3AccessKey = System.getenv("AWS_ACCESS_KEY_ID");
                }
                if (s3SecretKey == null) {
                    s3SecretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
                }
                if (s3SessionToken == null) {
                    s3SessionToken = System.getenv("AWS_SESSION_TOKEN");
                }
            } catch (NullPointerException e) {
                throw new MojoExecutionException("Failed to get the environment variables AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN", e);
            }

            BasicSessionCredentials basicSessionCredentials = new BasicSessionCredentials(s3AccessKey, s3SecretKey, s3SessionToken);
            return new AmazonS3Client(basicSessionCredentials);
        } else {
            if (s3AccessKey != null || s3SecretKey != null) {
                return new AmazonS3Client(new BasicAWSCredentials(s3AccessKey, s3SecretKey));
            } else {
                return new AmazonS3Client(new DefaultAWSCredentialsProviderChain());
            }
        }
    }

    /** Download the entire repository into the staging area. The paths for the files downloaded into the staging area
     * are <em>repo-relative</em> paths. (Also adds SNAPSHOT metadata to the provided <code>context</code>.) */
    private void downloadRepositories(RebuildContext context) throws MojoExecutionException {
        getLog().debug("Excluded files = " + context.getExcludedFiles());
        // NOTE: we download target repository first just in case both source and target share some files, we
        // want the target repository's files to override. (Download logic does not replace any local files.)
        // ALSO: we only download metadata files from the target repository (or target and source if they're
        // the same.)
        getLog().info("Downloading TARGET repository...");
        internalDownload(context, context.getS3TargetRepositoryPath(), /*isTargetRepo*/true); // target repo
        if (!context.sourceAndTargetRepositoryAreSame()) {
            getLog().info("Downloading SOURCE repository...");
            internalDownload(context, context.getS3RepositoryPath(),/*isTargetRepo=*/false); // source repo
        }
    }

    private void internalDownload(RebuildContext context, S3RepositoryPath s3RepositoryPath, boolean isTargetRepo)
            throws MojoExecutionException {
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
                .withBucketName(s3RepositoryPath.getBucketName());
        String prefix = ""; // capture prefix for debug logging
        if (s3RepositoryPath.hasBucketRelativeFolder()) {
            prefix = s3RepositoryPath.getBucketRelativeFolder() + "/";
            listObjectsRequest.withPrefix(prefix);
        }
        List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), listObjectsRequest);
        getLog().debug("Found " + result.size() + " objects in bucket '" + s3RepositoryPath.getBucketName()
                + "' with prefix '" + s3RepositoryPath.getBucketRelativeFolder() + "/" + "'...");
        for (S3ObjectSummary summary : result) {
            final String asRepoRelativePath = S3Utils.toRepoRelativePath(summary, s3RepositoryPath);
            if (summary.getKey().endsWith("/")) {
                getLog().info("Downloading: "
                    + s3RepositoryPath + "/" + asRepoRelativePath + " => (skipping; it's a folder)");
                continue;
            }
            final boolean isMetadataFile = isMetadataFile(summary, s3RepositoryPath);
            if (doNotValidate && isMetadataFile) {
                getLog().info("Downloading: "
                    + s3RepositoryPath + "/" + asRepoRelativePath + " => (metadata file and not validating, so will not download)");
                continue;
            }
            if (!isTargetRepo && isMetadataFile) {
                getLog().info("Downloading: "
                    + s3RepositoryPath + "/" + asRepoRelativePath + " => (metadata file in source repo; will not download)");
                continue;
            }
            if (context.getExcludedFiles().contains(asRepoRelativePath)) {
                getLog().info("Downloading: "
                    + s3RepositoryPath + "/" + asRepoRelativePath + " => (explicitly excluded; will be removed from S3)");
                if (isTargetRepo) {
                    // enqueue file for deletion only if it is in the target repo. (we never want to do remote mutation
                    // operations on the source repo if it is different than the target repo)
                    context.addExcludedFileToDelete(asRepoRelativePath, s3RepositoryPath);
                }
                continue;
            }
            // for every item in the repository, add it to our snapshot metadata if it's a snapshot artifact
            maybeAddSnapshotMetadata(summary, context, s3RepositoryPath);
            if (new File(stagingDirectory, asRepoRelativePath).isFile()) {
                // file exists (likely due to doNotPreClean = true); do not download
                getLog().info("Downloading: " + s3RepositoryPath + "/" + asRepoRelativePath + " => (skipping; already downloaded/exists)");
            } else {
                // file doesn't yet exist
                try {
                    downloadFile(context, s3RepositoryPath, asRepoRelativePath, isTargetRepo, summary);
                } catch (SocketTimeoutException e) {
                    // Retry due to socket timeout
                    getLog().warn("Retrying Download due to socket timeout: " + s3RepositoryPath + "/" + asRepoRelativePath);
                    try {
                        downloadFile(context, s3RepositoryPath, asRepoRelativePath, isTargetRepo, summary);
                    } catch (IOException ex) {
                        throw new MojoExecutionException("failed to download object from s3: " + summary.getKey(), ex);
                    }
                } catch (IOException e) {
                    throw new MojoExecutionException("failed to download object from s3: " + summary.getKey(), e);
                }
            }
        }
    }

    private void downloadFile(RebuildContext context, S3RepositoryPath s3RepositoryPath, String asRepoRelativePath, boolean isTargetRepo, S3ObjectSummary summary) throws IOException {
        final S3Object object = context.getS3Session()
                .getObject(new GetObjectRequest(s3RepositoryPath.getBucketName(), summary.getKey()));

        File targetFile = new File(stagingDirectory, asRepoRelativePath);
        Files.createParentDirs(targetFile);

        getLog().info("Downloading: " + s3RepositoryPath + "/" + asRepoRelativePath + " => " + targetFile);

        RequestConfig config = RequestConfig.custom()
                .setSocketTimeout(WellKnowns.SOCKET_TIMEOUT)
                .setConnectTimeout(WellKnowns.SOCKET_TIMEOUT)
                .build();

        final S3ObjectInputStream objectContent = object.getObjectContent();
        objectContent.getHttpRequest().setConfig(config);

        FileUtils.copyStreamToFile(new InputStreamFacade() {
            @Override
            public InputStream getInputStream() throws IOException {
                return objectContent;
            }
        }, targetFile);

        if (isTargetRepo) {
            context.addFileFromTargetRepo(targetFile);
        }
    }

    private boolean isMetadataFile(S3ObjectSummary summary, S3RepositoryPath repo) {
        final String metadataFilePrefix = repo.hasBucketRelativeFolder()
            ? repo.getBucketRelativeFolder() + "/" + WellKnowns.YUM_REPODATA_FOLDERNAME + "/"
            : WellKnowns.YUM_REPODATA_FOLDERNAME + "/";
        return summary.getKey().startsWith(metadataFilePrefix);
    }

    private void maybeAddSnapshotMetadata(S3ObjectSummary summary, RebuildContext context, S3RepositoryPath s3RepositoryPath) {
        final int lastSlashIndex = summary.getKey().lastIndexOf("/");
        // determine the path to the file (excluding the filename itself); this path may be empty, otherwise it contains
        // a "/" suffix
        final String path = lastSlashIndex > 0 ? summary.getKey().substring(0, lastSlashIndex + 1) : "";
        // determine the file name (without any directory path elements)
        final String fileName = lastSlashIndex > 0 ? summary.getKey().substring(lastSlashIndex + 1) : summary.getKey();
        final int snapshotIndex = fileName.indexOf("SNAPSHOT");
        if (snapshotIndex > 0) { // heuristic: we have a SNAPSHOT artifact here
            final String prefixWithoutPath = fileName.substring(0, snapshotIndex);
            final String bucketKeyPrefix = path + prefixWithoutPath;
            // try to convert anything after the SNAPSHOT into an ordinal value
            final int ordinal = toOrdinal(fileName.substring(snapshotIndex));
            getLog().debug("Making note of snapshot '" + summary.getKey() + "'; using prefix = " + bucketKeyPrefix);
            // ASSERT: bucketKeyPrefix is *full path* of bucket key up to and excluding the SNAPSHOT string and anything after it.
            context.addSnapshotDescription(
                new SnapshotDescription(s3RepositoryPath, summary.getBucketName(), bucketKeyPrefix, summary.getKey(), ordinal));
        }
    }

    private static int toOrdinal(String snapshotSuffix) {
        // replace all non-digits
        String digitsOnly = snapshotSuffix.replaceAll("\\D", "");
        if (StringUtils.isEmpty(digitsOnly)) {
            return -1;
        }
        try {
            return Integer.parseInt(digitsOnly);
        } catch (Exception e) {
            return -1;
        }
    }

    private static S3RepositoryPath parseS3RepositoryPath(String path) throws MojoExecutionException {
        try {
            return S3RepositoryPath.parse(path);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse S3 repository path: " + path, e);
        }
    }

    private String tryStripSnapshotNumerics(String snapshotFileName) {
        if (StringUtils.countMatches(snapshotFileName, "SNAPSHOT") != 1) {
            getLog().warn("filename did not look like a normal SNAPSHOT");
            return snapshotFileName; // do nothing
        }
        return snapshotFileName.replaceAll("SNAPSHOT\\d+\\.", "SNAPSHOT.");
    }

}
