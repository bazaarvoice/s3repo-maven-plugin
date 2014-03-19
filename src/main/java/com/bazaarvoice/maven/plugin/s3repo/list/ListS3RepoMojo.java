package com.bazaarvoice.maven.plugin.s3repo.list;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.bazaarvoice.maven.plugin.s3repo.S3RepositoryPath;
import com.bazaarvoice.maven.plugin.s3repo.WellKnowns;
import com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraFileUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.S3Utils;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
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
import java.util.List;
import java.util.Set;

@Mojo(name = "list-repo", requiresProject = false)
public final class ListS3RepoMojo extends AbstractMojo {

    /** Staging directory. This is where we will download any repo files that are needed (e.g., metadata files). */
    @Parameter(property = "s3repo.stagingDirectory")
    private File stagingDirectory;

    /**
     * The s3 path to the root of the target repository.
     * These are all valid values:
     * "s3://Bucket1/Repo1"
     * "/Bucket/Repo1"
     */
    @Parameter(property = "s3repo.repositoryPath", required = true)
    private String s3RepositoryPath;

    @Parameter(property = "s3repo.accessKey")
    private String s3AccessKey;

    @Parameter(property = "s3repo.secretKey")
    private String s3SecretKey;

    /** The createrepo executable. */
    @Parameter(property = "s3repo.createrepo", defaultValue = "createrepo")
    private String createrepo;

    @Parameter(property = "s3repo.pretty", defaultValue = "false")
    private boolean pretty;

    @Parameter(property = "s3repo.filterByMetadata", defaultValue = "true")
    private boolean filterByMetadata;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        determineAndSetStagingDirectoryIfNeeded();

        ListContext context = new ListContext();

        context.setS3Session(createS3Client());
        context.setS3RepositoryPath(parseS3RepositoryPath());
        context.setLocalYumRepo(determineLocalYumRepo(context.getS3RepositoryPath()));

        cleanStagingDirectory();
        maybeDownloadRepositoryMetadata(context);
        List<String> list = internalListRepository(context);
        print(list);
    }

    private void print(List<String> list) {
        getLog().info("[RESULT]");
        if (pretty) {
            for (String one : list) {
                getLog().info(one);
            }
        } else {
            getLog().info(Joiner.on(",").join(list));
        }
    }

    /** Return list of repo-relative file paths. */
    private List<String> internalListRepository(ListContext context) throws MojoExecutionException {
        List<String> list = Lists.newArrayList();
        S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
        Set<String> filesListedInMetadata = Sets.newHashSet(); // will remain empty if filterByMetadata = false
        if (filterByMetadata) {
            // assert: metadata is downloaded, so we can:
            filesListedInMetadata.addAll(context.getLocalYumRepo().parseFileListFromRepoMetadata());
            getLog().debug("files listed in metadata = " + filesListedInMetadata);
        }
        // note: filesListedInMetadata are **repo-relative** file paths.
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
            .withBucketName(s3RepositoryPath.getBucketName());
        String prefix = ""; // capture prefix for debug logging
        if (s3RepositoryPath.hasBucketRelativeFolder()) {
            prefix = s3RepositoryPath.getBucketRelativeFolder() + "/";
            listObjectsRequest.withPrefix(prefix);
        }
        List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), listObjectsRequest);
        for (S3ObjectSummary summary : result) {
            if (summary.getKey().endsWith("/")) {
                getLog().debug("Will not list " + summary.getKey() + ", it's a folder");
                continue;
            }
            if (isMetadataFile(summary, s3RepositoryPath)) {
                getLog().debug("Will not list " + summary.getKey() + ", it's a metadata file");
                continue;
            }
            String asRepoRelativeFile =
                s3RepositoryPath.hasBucketRelativeFolder()
                    ? summary.getKey().replaceFirst("^\\Q" + s3RepositoryPath.getBucketRelativeFolder() + "/\\E", "")
                    : summary.getKey();
            if (filterByMetadata && !filesListedInMetadata.contains(asRepoRelativeFile)) {
                getLog().debug("Not known to metadata: " + summary.getKey() + " (repo-relative: " + asRepoRelativeFile + ")");
            }
            // Assert: summary.getKey() is a file that exists as a file in the S3 repo AND
            // it is listed in the YUM metadata for the repo.
            list.add(asRepoRelativeFile);
        }
        return list;
    }

    private void cleanStagingDirectory() throws MojoExecutionException {
        ExtraFileUtils.createOrCleanDirectory(stagingDirectory);
    }

    private void maybeDownloadRepositoryMetadata(ListContext context) throws MojoExecutionException {
        if (!filterByMetadata) {
            getLog().info("Will not filter file list using YUM metadata.");
            return;
        }
        S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
            .withBucketName(s3RepositoryPath.getBucketName());
        String prefix = ""; // capture prefix for debug logging
        if (s3RepositoryPath.hasBucketRelativeFolder()) {
            prefix = s3RepositoryPath.getBucketRelativeFolder() + "/" + WellKnowns.YUM_REPODATA_FOLDERNAME + "/";
            listObjectsRequest.withPrefix(prefix);
        }
        List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), listObjectsRequest);
        for (S3ObjectSummary summary : result) {
            if (summary.getKey().endsWith("/")) {
                getLog().debug("Will not list " + summary.getKey() + ", it's a folder");
                continue;
            }
            getLog().info("Downloading metadata file '" + summary.getKey() + "' from S3...");
            final S3Object object = context.getS3Session()
                .getObject(new GetObjectRequest(s3RepositoryPath.getBucketName(), summary.getKey()));
            try {
                File targetFile =
                    new File(stagingDirectory, /*assume object key is bucket-relative path to filename with extension*/summary.getKey());
                Files.createParentDirs(targetFile);
                FileUtils.copyStreamToFile(new InputStreamFacade() {
                    @Override
                    public InputStream getInputStream()
                        throws IOException {
                        return object.getObjectContent();
                    }
                }, targetFile);
            } catch (IOException e) {
                throw new MojoExecutionException("failed to download object from s3: " + summary.getKey(), e);
            }
        }
    }

    private void determineAndSetStagingDirectoryIfNeeded() {
        if (stagingDirectory == null) {
            stagingDirectory = Files.createTempDir();
        }
        getLog().info("I will use " + stagingDirectory.getAbsolutePath() + " as your staging directory.");
    }

    /**
     * Create a {@link com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade} which will allow us to query and
     * operate on a local (on-disk) yum repository.
     */
    private LocalYumRepoFacade determineLocalYumRepo(S3RepositoryPath s3RepositoryPath) {
        return new LocalYumRepoFacade(
            s3RepositoryPath.hasBucketRelativeFolder()
                ? new File(stagingDirectory, s3RepositoryPath.getBucketRelativeFolder())
                : stagingDirectory, createrepo, "", getLog());
    }

    private boolean isMetadataFile(S3ObjectSummary summary, S3RepositoryPath s3RepositoryPath) {
        final String metadataFilePrefix = s3RepositoryPath.hasBucketRelativeFolder()
            ? s3RepositoryPath.getBucketRelativeFolder() + "/" + WellKnowns.YUM_REPODATA_FOLDERNAME + "/"
            : WellKnowns.YUM_REPODATA_FOLDERNAME + "/";
        return summary.getKey().startsWith(metadataFilePrefix);
    }

    private AmazonS3Client createS3Client() {
        if (s3AccessKey != null || s3SecretKey != null) {
            return new AmazonS3Client(new BasicAWSCredentials(s3AccessKey, s3SecretKey));
        } else {
            return new AmazonS3Client(new DefaultAWSCredentialsProviderChain());
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

}
