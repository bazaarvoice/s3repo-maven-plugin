package com.bazaarvoice.maven.plugin.s3repo.create;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
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
import com.google.common.io.Files;
import org.apache.http.client.config.RequestConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.io.InputStreamFacade;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates or updates a YUM repository in S3.
 */
@Mojo(name = "create-update", defaultPhase = LifecyclePhase.DEPLOY)
public class CreateOrUpdateS3RepoMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(property = "session.repositorySession", required = true, readonly = true)
    private RepositorySystemSession session;

    /** Staging directory. This is where we will generate *bucket-relative* files. */
    @Parameter(property = "s3repo.stagingDirectory", defaultValue = "${project.build.directory}/s3repo")
    private File stagingDirectory;

    /** Whether or not this goal should be allowed to create a new repository if it's needed. */
    @Parameter(property = "s3repo.allowCreateRepository", defaultValue = "false")
    private boolean allowCreateRepository;

    /** Auto increment snapshot dependencies. */
    @Parameter(property = "s3repo.autoIncrementSnapshotArtifacts", defaultValue = "true")
    private boolean autoIncrementSnapshotArtifacts;

    /** Fail create/update if updated repo verification fails. */
    @Parameter(property = "s3repo.ignoreVerificationFailures", defaultValue = "false")
    private boolean ignoreVerificationFailures;

    @Parameter(required = true)
    private List<ArtifactItem> artifactItems;

    /**
     * The s3 path to the root of the target repository.
     * These are all valid values:
     *      "s3://Bucket1/Repo1"
     *      "/Bucket/Repo1"
     * This goal does not create buckets; the plugin goal execution will fail if the bucket does not exist in S3.
     * Note that {@link #artifactItems} can optionally specify a per-artifact repositoryPath-relative target subfolder.
     */
    @Parameter(property = "s3repo.repositoryPath", required = true)
    private String s3RepositoryPath;

    @Parameter(property = "s3repo.accessKey")
    private String s3AccessKey;

    @Parameter(property = "s3repo.secretKey")
    private String s3SecretKey;

    @Parameter(property = "s3repo.sessionToken")
    private String s3SessionToken;

    @Parameter(property = "s3repo.awsStsFlag", defaultValue = "false")
    private boolean awsStsFlag;

    /** Execute all steps up to and excluding the upload to the S3. This can be set to true to perform a "dryRun" execution. */
    @Parameter(property = "s3repo.doNotUpload", defaultValue = "false")
    private boolean doNotUpload;

    /** The createrepo executable. */
    @Parameter(property = "s3repo.createrepo", defaultValue = "createrepo")
    private String createrepo;

    /** Additional options for the createrepo command. See http://linux.die.net/man/8/createrepo. */
    @Parameter(property = "s3repo.createrepoOpts", defaultValue = "")
    private String createrepoOpts;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        CreateOrUpdateContext context = new CreateOrUpdateContext();

        // parse s3 repository path and set bucketAndFolder field
        context.setS3Session(createS3Client());
        context.setS3RepositoryPath(parseS3RepositoryPath());
        context.setLocalYumRepo(determineLocalYumRepo());

        // always clean the staging directory -- it never makes sense to start with existing staging directory
        ExtraFileUtils.createOrCleanDirectory(stagingDirectory);

        // require S3 bucket to exist before continuing
        ensureS3BucketExists(context);
        // download existing repository metadata
        pullExistingRepositoryMetadata(context);
        // require existing repository metadata if allowCreateRepository = false
        maybeEnsureExistingRepositoryMetadata(context);
        // synthesize/touch zero-size files to represent existing repository-managed files
        synthesizeExistingRepositoryFiles(context);
        // save some stats about the original repo
        final RepoStatistics originalRepoStatistics = RepoStatistics.createRepoStatisticsFromCreateOrUpdateContext(context);
        // resolve artifacts, copy to staging directory
        resolveAndCopyArtifactItems(context);
        // create the actual repository
        createRepo(context);
        // save some stats about the updated repo
        final RepoStatistics updatedRepoStatistics = RepoStatistics.createRepoStatisticsFromCreateOrUpdateContext(context);
        // pathologically delete files that we do not wish to push to target repository
        cleanupSynthesizedFiles(context);
        // verify that the repo we created is sane
        verifyUpdatedRepo(originalRepoStatistics, updatedRepoStatistics);
        // push/upload staging directory to repository if doNotUpload = false
        maybeUploadRepositoryUpdate(context);
    }

    /** Create a {@link LocalYumRepoFacade} which will allow us to query and operate on a local (on-disk) yum repository. */
    private LocalYumRepoFacade determineLocalYumRepo() {
        return new LocalYumRepoFacade(stagingDirectory, createrepo, createrepoOpts, getLog());
    }

    private void maybeUploadRepositoryUpdate(CreateOrUpdateContext context) throws MojoExecutionException {
        String logPrefix = "";
        if (doNotUpload) {
            getLog().info("Per configuration, we will NOT perform any remote operations on the S3 repository.");
            logPrefix = "SKIPPING: ";
        }
        final S3RepositoryPath targetRepository = context.getS3RepositoryPath();
        final String targetBucket = targetRepository.getBucketName();
        AmazonS3 s3Session = context.getS3Session();
        for (File toUpload : ExtraIOUtils.listAllFiles(stagingDirectory)) {
            String bucketKey = localFileToTargetS3BucketKey(toUpload, targetRepository);
            getLog().info(logPrefix + "Uploading: " + toUpload.getName() + " => s3://" + targetRepository.getBucketName() + "/" + bucketKey + "...");
            if (!doNotUpload) {
                s3Session.putObject(new PutObjectRequest(targetBucket, bucketKey, toUpload).withCannedAcl(CannedAccessControlList.BucketOwnerFullControl));
            }
        }
    }

    /** Convert local file in staging directory to bucket key (in target s3 repository). */
    private String localFileToTargetS3BucketKey(File toUpload, S3RepositoryPath repo) throws MojoExecutionException {
        String relativizedPath = ExtraIOUtils.relativize(stagingDirectory, toUpload);
        // replace *other* file separators with S3-style file separators and strip first & last separator
        relativizedPath = relativizedPath.replaceAll("\\\\", "/").replaceAll("^/", "").replaceAll("/$", "");
        return repo.hasBucketRelativeFolder()
            ? repo.getBucketRelativeFolder() + "/" + relativizedPath
            : relativizedPath;
    }

    private void cleanupSynthesizedFiles(CreateOrUpdateContext context) throws MojoExecutionException {
        for (File synthesizedFile : context.getSynthesizedFiles()) {
            if (!synthesizedFile.delete()) {
                throw new MojoExecutionException("Failed to delete synthesized file: " + synthesizedFile);
            }
        }
    }

    private void synthesizeExistingRepositoryFiles(CreateOrUpdateContext context) throws MojoExecutionException {
        // Here's what we'll do in this method:
        //      1) parse "repodata/repomd.xml" to determine the primary metadata file (typically "repodata/primary.xml.gz")
        //      2) extract a file list from the primary metadata file
        //      3) ensure that all files in the primary file list exist in the s3 repo
        //      4) "touch"/synthesize a zero-sized file for each file in the primary list
        if (context.getLocalYumRepo().isRepoDataExists()) { // if repo exists...
            // determine primary metadata file from metadata xml and parse it to determine repository files *declared* by the metadata
            List<String> repoRelativeFilePathList = context.getLocalYumRepo().parseFileListFromRepoMetadata();
            S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
            ListObjectsRequest request = new ListObjectsRequest()
                    .withBucketName(context.getS3RepositoryPath().getBucketName());
            if (s3RepositoryPath.hasBucketRelativeFolder()) {
                request.withPrefix(s3RepositoryPath.getBucketRelativeFolder() + "/");
            }
            List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), request);
            // we will start with a set of metadata-declared files and remove any file we find that exists in the repo;
            // we expect the Set to be empty when finished iteration. note that s3 api returns bucket-relative
            // paths, so we prefix each of our repoRelativeFilePaths with the repository path.
            Set<String> bucketRelativePaths = new HashSet<String>();
            for (String repoRelativeFilePath : repoRelativeFilePathList) {
                if (s3RepositoryPath.hasBucketRelativeFolder()) {
                    bucketRelativePaths.add(s3RepositoryPath.getBucketRelativeFolder() + "/" + repoRelativeFilePath);
                } else {
                    bucketRelativePaths.add(repoRelativeFilePath);
                }
            }
            // for each bucket relative path in the listObjects result, remove from our set
            for (S3ObjectSummary summary : result) {
                bucketRelativePaths.remove(summary.getKey());
            }
            // now, expect set to be empty
            if (!bucketRelativePaths.isEmpty()) {
                throw new MojoExecutionException("Primary metadata file declared files that did not exist in the repository: " + bucketRelativePaths);
            }
            // for each file in our repoRelativeFilePathList, touch/synthesize the file
            for (String repoRelativeFilePath : repoRelativeFilePathList) {
                File file = new File(stagingDirectory, repoRelativeFilePath);
                if (file.exists()) {
                    throw new MojoExecutionException("Repo already has this file: " + file.getPath());
                }
                ExtraIOUtils.touch(file);
                context.addSynthesizedFile(file);
            }
        }
    }

    private void verifyUpdatedRepo(final RepoStatistics originalRepoStatistics, final RepoStatistics updatedRepoStatistics) throws MojoExecutionException {
        final int packages = updatedRepoStatistics.getNumPackages();
        final int expectedPackages = originalRepoStatistics.getNumPackages() + artifactItems.size();
        // sanity check to ensure that the createrepo command worked
        if (packages != expectedPackages) {
            final String msg = "Updated repo metadata has " + packages + " packages, expected " + expectedPackages;
            getLog().warn(msg);
            if (!ignoreVerificationFailures) {
                throw new MojoExecutionException(msg);
            }
        }
    }

    private S3RepositoryPath parseS3RepositoryPath() throws MojoExecutionException {
        try {
            S3RepositoryPath parsed = S3RepositoryPath.parse(s3RepositoryPath);
            if (parsed.hasBucketRelativeFolder()) {
                getLog().info("Using bucket '" + parsed.getBucketName() + "' and folder '" + parsed.getBucketRelativeFolder() + "' as repository...");
            } else {
                getLog().info("Using bucket '" + parsed.getBucketName() + "' as repository...");
            }
            return parsed;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse S3 repository path: " + s3RepositoryPath, e);
        }
    }

    private void resolveAndCopyArtifactItems(CreateOrUpdateContext context) throws MojoExecutionException {
        copyArtifactItems(context, resolveArtifactItems(artifactItems));
    }

    private void maybeEnsureExistingRepositoryMetadata(CreateOrUpdateContext context) throws MojoExecutionException {
        if (!allowCreateRepository) {
            if (!context.getLocalYumRepo().isRepoDataExists()) {
                throw new MojoExecutionException("Repository folder " + context.getS3RepositoryPath().getBucketRelativeFolder() +
                    " is not an existing repository (i.e., it doesn't a contain " + WellKnowns.YUM_REPODATA_FOLDERNAME + " folder)," +
                        " use allowCreateRepository = true to create");
            }
        }
    }

    private void copyArtifactItems(CreateOrUpdateContext context, List<ArtifactItem> resolvedArtifactItems) throws MojoExecutionException {
        for (ArtifactItem artifactItem : resolvedArtifactItems) {
            try {
                // if a targetBaseName isn't specified, use <artifactID>-<version> as extensionless filename
                final String baseFileName = artifactItem.hasTargetBaseName()
                            ? artifactItem.getTargetBaseName()
                            : artifactItem.getArtifactId() + "-" + artifactItem.getVersion();
                int snaphshotIndex = 0;
                File targetFile;
                do {
                    String baseFileNameToUse = baseFileName;
                    if (artifactItem.isSnapshot() && autoIncrementSnapshotArtifacts && snaphshotIndex > 0 /*never suffix with 0*/) {
                        // snapshots are treated specially -- given an incrementing suffix that will be incremented on collisions
                        baseFileNameToUse = baseFileName + snaphshotIndex;
                    }
                    // create filename from dependency's file name but using pom-configured target subfolder and target extension
                    String targetFileName = baseFileNameToUse + "." + artifactItem.getTargetExtension();
                    final File targetDirectory = !StringUtils.isEmpty(artifactItem.getTargetSubfolder())
                            ? new File(stagingDirectory, artifactItem.getTargetSubfolder())
                            : stagingDirectory;
                    targetFile = new File(targetDirectory, targetFileName);
                    if (targetFile.exists()) {
                        if (!artifactItem.isSnapshot() || !autoIncrementSnapshotArtifacts) {
                            // fail on file collisions!
                            throw new MojoExecutionException("Dependency " + artifactItem.getResolvedArtifact().getArtifact() + " already exists in repository!");
                        }
                        // file is a snapshot; increment snapshotIndex retry targetFile
                        ++snaphshotIndex;
                    } else {
                        // targetFile does not exist; we will copy to this file
                        break;
                    }
                } while (true);
                getLog().info("Copying artifact to " + targetFile.getPath() + "...");
                FileUtils.copyFile(artifactItem.getResolvedArtifact().getArtifact().getFile(), targetFile);
            } catch (IOException e) {
                throw new MojoExecutionException("failed to copy artifact " + artifactItem + " to target", e);
            }
        }
    }

    private List<ArtifactItem> resolveArtifactItems(List<ArtifactItem> artifactItems) throws MojoExecutionException {
        // resolved artifacts have been downloaded and are available locally
        for (ArtifactItem item : artifactItems) {
            try {
                item.setResolvedArtifact(repositorySystem.resolveArtifact(session, toArtifactRequest(item)));
            } catch (ArtifactResolutionException e) {
                throw new MojoExecutionException("couldn't resolve: " + item, e);
            }
        }
        return artifactItems;
    }

    private ArtifactRequest toArtifactRequest(ArtifactItem item) {
        return new ArtifactRequest(toDefaultArtifact(item), project.getRemoteProjectRepositories(), "project");
    }

    private org.eclipse.aether.artifact.Artifact toDefaultArtifact(ArtifactItem item) {
        return new DefaultArtifact(item.getGroupId(), item.getArtifactId(), item.getClassifier(), item.getType()/*extension*/, item.getVersion());
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

    private void ensureS3BucketExists(CreateOrUpdateContext context) throws MojoExecutionException {
        if (!context.getS3Session().doesBucketExist(context.getS3RepositoryPath().getBucketName())) {
            throw new MojoExecutionException("Bucket doesn't exist in S3: " + context.getS3RepositoryPath().getBucketName());
        }
    }

    private void pullExistingRepositoryMetadata(CreateOrUpdateContext context) throws MojoExecutionException {
        S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
        // build bucket-relative metadata folder path *with "/" suffix*
        String bucketRelativeMetadataFolderPath = WellKnowns.YUM_REPODATA_FOLDERNAME + "/";
        if (s3RepositoryPath.hasBucketRelativeFolder()) {
            // prefix repodata/ with repository folder
            bucketRelativeMetadataFolderPath = s3RepositoryPath.getBucketRelativeFolder() + "/" + bucketRelativeMetadataFolderPath;
        }
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
                .withBucketName(s3RepositoryPath.getBucketName())
                .withPrefix(bucketRelativeMetadataFolderPath/*, which has "/" suffix*/);
        List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), listObjectsRequest);
        getLog().debug("Found " + result.size() + " objects in bucket '" + s3RepositoryPath.getBucketName()
                + "' with prefix '" + bucketRelativeMetadataFolderPath + "'...");
        for (S3ObjectSummary summary : result) {
            final String asRepoRelativePath = S3Utils.toRepoRelativePath(summary, s3RepositoryPath);
            if (summary.getKey().endsWith("/")) {
                getLog().info("Downloading: "
                    + s3RepositoryPath + "/" + asRepoRelativePath + " => (skipping; it's a folder)");
                continue;
            }
            final S3Object object = context.getS3Session()
                    .getObject(new GetObjectRequest(s3RepositoryPath.getBucketName(), summary.getKey()));
            try {
                File targetFile = new File(stagingDirectory, asRepoRelativePath);
                getLog().info("Downloading: " + s3RepositoryPath + "/" + asRepoRelativePath + " => " + targetFile);
                Files.createParentDirs(targetFile);

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
            } catch (IOException e) {
                throw new MojoExecutionException("failed to download object from s3: " + summary.getKey(), e);
            }
        }
    }

    private void createRepo(CreateOrUpdateContext context) throws MojoExecutionException {
        if (context.getLocalYumRepo().isRepoDataExists()) {
            context.getLocalYumRepo().updateRepo();
        } else {
            context.getLocalYumRepo().createRepo();
        }
    }

}
