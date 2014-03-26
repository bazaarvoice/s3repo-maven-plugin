package com.bazaarvoice.maven.plugin.s3repo;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.eclipse.aether.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class S3RepositoryPath {

    /**
     * Parses any form like:
     *      - "s3://Bucket/path
     *      - "/Bucket/path"
     *      - "/Bucket/path/"
     * Fails otherwise.
     */
    public static S3RepositoryPath parse(String form) {
        if (form.startsWith("s3://")) {
            form = form.substring("s3://".length());
        } else if (form.startsWith("/")) {
            form = form.substring("/".length());
        } else {
            throw new IllegalArgumentException("Expected '/' or 's3://' prefix for S3 repository path: " + form);
        }
        List<String> pieces = splitPath(form);
        if (pieces.size() < 1) {
            throw new IllegalArgumentException("Could not parse S3 repository path: " + form);
        }
        StringBuilder folderPath = new StringBuilder();
        String separator = "";
        for (int i = 1/*everything beyond bucket name*/; i < pieces.size(); ++i) {
            String piece = pieces.get(i);
            if (!StringUtils.isEmpty(piece)) {
                folderPath.append(separator).append(piece);
                separator = "/";
            }
        }
        return new S3RepositoryPath(pieces.get(0), folderPath.toString() /*may be empty*/);
    }

    private final String bucketName;

    /** The folder path with no path-separator prefixing or suffixing the path. For example, "path/to/folder".
     * May be empty but never null! */
    private final String bucketRelativeFolder;

    public S3RepositoryPath(String bucketName, String bucketRelativeFolder) {
        this.bucketName = bucketName;
        this.bucketRelativeFolder = bucketRelativeFolder;
    }

    public String getBucketName() {
        return bucketName;
    }

    public boolean hasBucketRelativeFolder() {
        return !StringUtils.isEmpty(bucketRelativeFolder);
    }

    /**
     * The folder path with no path-separator prefixing or suffixing the path.
     * It may be empty indicating no subfolder.
     */
    public String getBucketRelativeFolder() {
        return bucketRelativeFolder;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @Override
    public String toString() {
        return "s3://" + bucketName + (hasBucketRelativeFolder() ? "/" + bucketRelativeFolder : "");
    }

    /** *Tolerant* path splitter that gets rid of empty parts. */
    private static List<String> splitPath(String path) {
        String[] parts = path.split("/");
        List<String> retval = new ArrayList<String>(parts.length);
        for (String part : parts) {
            if (!StringUtils.isEmpty(part)) {
                retval.add(part);
            }
        }
        return retval;
    }

}
