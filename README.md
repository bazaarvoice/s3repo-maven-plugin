s3repo-maven-plugin
===================

This Maven plugin supports releasing and deploying to YUM repositories hosted in S3. Additional goals allow you to
rebuild, relocate, and list S3-based YUM repositories.

The latest version is **3.6**.

Note that using S3 as a YUM repository is possible via the YUM plugin
[yum-s3-plugin](https://github.com/jbraeuer/yum-s3-plugin).

Goals
=====
* __create-update__ - Creates or updates an S3 YUM repository.
* __rebuild-repo__ - Rebuilds (and, optionally, _relocates_) an existing S3 YUM repository.
* __list-repo__ - List the contents of an S3 YUM repository.

create-update: Usage Example
============================

Here is a common configuration:

    <plugin>
        <groupId>com.bazaarvoice.maven.plugins</groupId>
        <artifactId>s3repo-maven-plugin</artifactId>
        <version>${VERSION}</version> <!-- use latest version instead -->
        <executions>
            <execution>
                <phase>package</phase>
                <goals>
                    <goal>create-update</goal>
                </goals>
                <configuration>
                    <s3RepositoryPath>/MyBucket/myRepository</s3RepositoryPath>
                    <artifactItems>
                        <artifactItem>
                            <groupId>${yourGroupdId}</groupId>
                            <artifactId>${yourArtifactId}</artifactId>
                            <version>${yourVersion}</version>
                            <type>rpm</type>
                            <classifier>rpm</classifier>
                            <targetExtension>noarch.rpm</targetExtension>
                        </artifactItem>
                    </artifactItems>
                </configuration>
            </execution>
        </executions>
    </plugin>

create-update: Special Notes
============================

* If one of your declared artifact items *already* exists in the S3 YUM repository, the goal will fail unless the declared
  artifact item is a *SNAPSHOT* dependency and the "autoIncrementSnapshotArtifacts" configuration property is true (this
  is the default value/behavior).
* You can use "-Ds3repo.allowCreateRepository=true" the first time you run the plugin to initialize a new repository; subsequent
  runs for a project can leave this value at its default (false) for extra safety.

create-update: Full Usage Example
=================================

Here is a full configuration demonstrating all possible configuration options. See the inline comments for further
explanation:

    <plugin>
        <groupId>com.bazaarvoice.maven.plugins</groupId>
        <artifactId>s3repo-maven-plugin</artifactId>
        <version>${VERSION}</version> <!-- use latest version instead -->
        <executions>
            <execution>
                <phase>deploy</phase> <!-- phase is optional; deploy is the default -->
                <goals>
                    <goal>create-update</goal>
                </goals>
                <configuration>
                    <!--
                        Optional. Allow this plugin to create the repository directory in S3 if it doesn't exist.
                        The default is false. Note that the target *bucket* must always exist; this plugin will
                        *never* create a bucket on your behalf.
                    -->
                    <allowCreateRepository>true</allowCreateRepository>
                    <!--
                        Optional. Whether or not to auto-increment snaphsot artifacts. For example, if your repository
                        contains foo-1.0-SNAPSHOT.noarch.rpm and you are attempting to deploy this artifact again, a numeric
                        index will be added to your snapshot to avoid name collision (e.g., foo-1.0-SNAPSHOT1.noarch.rpm).
                        The default value is true.
                    -->
                    <autoIncrementSnapshotArtifacts>true</autoIncrementSnapshotArtifacts>
                    <!--
                        Optional. You may wish to perform a "dry run" execution without uploading any files to your repository.
                        You will likely do this from the command-line using "-Ds3repo.doNotUpload=true".
                    -->
                    <doNotUpload>false</doNotUpload>
                    <!--
                        Optional. You may need to specify an alternate path for the "createrepo" command.
                    -->
                    <createrepo>/usr/bin/createrepo</createrepo>
                    <!--
                        Optional. You may need to provide additional options to the "createrepo" command.
                    -->
                    <createrepoOpts>--simple-md-filenames --no-database</createrepoOpts>
                    <!--
                        The S3 path to your repository. The first path entry is the *bucket*; optional
                        subpaths may indicate a repository that is not at the root/bucket level.
                        Examples; any of these are valid:
                            /MyBucket/myRepository
                            /MyBucket
                            s3://MyBucket/myRepository
                    -->
                    <s3RepositoryPath>/MyBucket/myRepository</s3RepositoryPath>
                    <!--
                        You can specify your access and secret keys in the POM but this is unadvised.
                        Use "-Ds3repo.accessKey=YOURKEY -Ds3repo.secretKey=YOURKEY" on the command-line instead.
                    -->
                    <s3AccessKey>${yourS3AccessKey}</s3AccessKey>
                    <s3SecretKey>${yourS3SecretKey}</s3SecretKey>
                    <!--
                        Optional. Enable this flag to pass AWS STS Authentication Credentials.
                        AWS STS Temporary Authentication has AccessKey, SecretKey and SecurityToken
                        Use "-Ds3repo.awsStsFlag=true" on the command-line instead
                    -->
                    <awsStsFlag>false<awsStsFlag>
                    <!--
                        Optional. Pass the SecurityToken, if awsStsFlag is enabled to true.
                        Use "-Ds3repo.s3SessionToken=YOURTEMPORARYSESSIONTOKEN" on the command-line instead
                    -->
                    <s3SessionToken>${yourtemporarySessionToken}</s3SessionToken>
                    <!--
                        You can specify multiple artifact items to copy to the repo.
                    -->
                    <artifactItems>
                        <artifactItem>
                            <groupId>${yourGroupId}</groupId>
                            <artifactId>${yourArtifactId}</artifactId>
                            <version>${yourVersion}</version>
                            <type>rpm</type>
                            <classifier>rpm</classifier>
                            <!--
                                Optional target base file name to use for the installable; it defaults to <artifactId>-<version>.
                            -->
                            <targetBaseName>${yourTargetBaseName}</targetBaseName>
                            <!--
                                Optional target extension to use for your artifact; it defaults to "noarch.rpm".
                                The artifact name that is added to the repository will be <yourTargetBaseName>-<targetExtension>.
                            -->
                            <targetExtension>noarch.rpm</targetExtension>
                            <!--
                                You may optionally specify a target folder for each artifactItem.
                                This is useful for organizing separate projects' packages/rpms into separate folders in
                                a single repository.
                             -->
                            <targetSubfolder>myProject</targetSubfolder>
                        </artifactItem>
                    </artifactItems>
                </configuration>
            </execution>
        </executions>
    </plugin>

rebuild-repo: Usage Examples
============================

This goal can be run from any directory; it does not need a Maven project/POM to run.

A simple example:

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:rebuild-repo \
        -Ds3repo.repositoryPath=s3://BucketName/yum-repo \
        -Ds3repo.accessKey=ABC \
        -Ds3repo.secretKey=DEF

If you want to clean up old snapshots, use:

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:rebuild-repo \
        -Ds3repo.repositoryPath=s3://BucketName/yum-repo \
        -Ds3repo.accessKey=ABC \
        -Ds3repo.secretKey=DEF \
        -Ds3repo.removeOldSnapshots=true \
        -Ds3repo.removeOldRepodata=true

If you want to use a non-temp staging directory:

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:rebuild-repo \
        -Ds3repo.repositoryPath=s3://BucketName/yum-repo \
        -Ds3repo.accessKey=ABC \
        -Ds3repo.secretKey=DEF \
        -Ds3repo.stagingDirectory=/path/to/staging/dir

A verbose example:

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:rebuild-repo \
        -Ds3repo.repositoryPath=s3://BucketName/yum-repo \
        -Ds3repo.accessKey=ABC \
        -Ds3repo.secretKey=DEF \
        -Ds3repo.removeOldSnapshots=true \
        -Ds3repo.removeOldRepodata=true \
        -Ds3repo.doNotValidate=true \
        -Ds3repo.doNotUpload=true \
        -Ds3repo.doNotPreClean=true \
        -Ds3repo.createrepo=/usr/bin/createrepo \
        -Ds3repo.createrepoOpts="--simple-md-filenames --no-database" \
        -Ds3repo.excludes=repo/relative/path/my-artifact-1.0.noarch.rpm,repo/relative/path/another-artifact-5.3.noarch.rpm

You can use "s3repo.doNotUpload" to rebuild the repository locally but not upload it. Use "s3repo.doNotValidate"
to rebuild the repository but not fail if existing repo metadata is missing or corrupt. Use "s3repo.doNotPreClean" in addition to "-Ds3repo.stagingDirectory" to avoid downloading artifacts that you've previously downloaded.

You can use "s3repo.excludes" to specify a comma-delimted list of repo-relative paths to omit when rebuilding the repo. The
listed paths will be removed/deleted from the target S3 bucket. A common idiom is to use the "list-repo" goal (see below)
to produce a comma-delimited list of ALL artifacts and then edit that list to desired exclusions to use in the rebuild-repo
execution.

You can use "s3repo.awsStsFlag=true" and specify a Temporary Session Token as "s3repo.sessionToken=YOURTOKEN" to force the plugin to use accesskey, SecretKey and SessionToken for Authentication.

Use "removeOldRepodata" to cleanup old repodata which accumulates over time.

Relocating a Repository
=======================

To relocate an existing S3 YUM repository, use the "s3repo.targetRepositoryPath" property to specify a target
repository that differs from the source repository. Note that this will add only those items in the source repo
that do not already exist in the target repo. Also note that you can do a "dry run" using the
"s3repo.doNotUpload" property.

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:rebuild-repo \
        -Ds3repo.repositoryPath=s3://some-artifacts/yum-repo \
        -Ds3repo.targetRepositoryPath=s3://other-artifacts/new-yum-repo \
        -Ds3repo.accessKey=${ACCESS} -Ds3repo.secretKey=${SECRET}

Listing a Repository
====================

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:list-repo \
        -Ds3repo.repositoryPath=s3://some-artifacts/yum-repo \
        -Ds3repo.accessKey=${ACCESS} -Ds3repo.secretKey=${SECRET}

This will produce a comma-delimited list that can modified and used in the "s3repo.excludes" configuration property when
executing the rebuild-repo goal.

Here is a verbose example:

    $ mvn com.bazaarvoice.maven.plugins:s3repo-maven-plugin:${VERSION}:list-repo \
        -Ds3repo.repositoryPath=s3://some-artifacts/yum-repo \
        -Ds3repo.pretty=true \
        -Ds3repo.filterByMetadata=false \
        -Ds3repo.accessKey=${ACCESS} -Ds3repo.secretKey=${SECRET}

The "s3repo.filterByMetadata" property is true by default. By setting it to false, all of the files in the repo will be listed,
not just those listed in the YUM metadata (typically this is not what is desired.)

Wishlist
========
* upload arbitrary RPM to repository without needing a Maven project/POM (i.e., in the Mojo, requiresProject = false)
* consider no pre-clean by default; but consider safety of this &mdash; comparing file hashes and removing local files
