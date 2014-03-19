package com.bazaarvoice.maven.plugin.s3repo.support;

import com.bazaarvoice.maven.plugin.s3repo.WellKnowns;
import com.bazaarvoice.maven.plugin.s3repo.util.LogStreamConsumer;
import com.bazaarvoice.maven.plugin.s3repo.util.NullStreamConsumer;
import com.bazaarvoice.maven.plugin.s3repo.util.SimpleNamespaceResolver;
import com.bazaarvoice.maven.plugin.s3repo.util.XmlUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Encapsulates queries and operations over a local copy of a YUM repo. */
public final class LocalYumRepoFacade {

    private final File repositoryRoot;
    private final String createRepoCommand;
    private final Set<String> createRepoArgs;
    private final Log log;

    public LocalYumRepoFacade(File repositoryRoot, String createRepoCommand, String createRepoOpts, Log log) {
        this.log = log;
        this.repositoryRoot = repositoryRoot;
        this.createRepoCommand = createRepoCommand;

        ImmutableSet.Builder<String> opts = ImmutableSet.builder();
        if (StringUtils.isNotEmpty(createRepoOpts)) {
            for (String opt : createRepoOpts.split("\\s")) {
                if (StringUtils.isNotEmpty(opt)) {
                    Preconditions.checkArgument(opt.startsWith("-"), "Option \"" + opt + "\" invalid. You may only provide options, not arguments.");
                    opts.add(opt);
                }
            }
        }
        this.createRepoArgs = opts.build();
    }

    public boolean isRepoDataExists() {
        // as a hueristic, answer true if repo metadata file exists
        return determineRepoMetadataFile().isFile();
    }

    public boolean hasFile(String repoRelativePath) {
        return new File(repositoryRoot, repoRelativePath).isFile();
    }

    /** Parse primary metadata file to get list of repo file paths (these paths will be *repo-relative*). */
    public List<String> parseFileListFromRepoMetadata() throws MojoExecutionException {
        File repoMetadataFile = determineRepoMetadataFile();
        if (!repoMetadataFile.isFile()) {
            throw new IllegalStateException("File didn't exist: " + repoMetadataFile.getPath());
        }
        return extractFileListFromPrimaryMetadataFile(
            XmlUtils.parseXmlFile(resolvePrimaryMetadataFile(XmlUtils.parseXmlFile(repoMetadataFile))));
    }

    /** Execute the createrepo command. */
    public void createRepo() throws MojoExecutionException {
        internalCreateRepo(false/*no update*/);
    }

    /** Execute the createrepo command in *update-only* mode. */
    public void updateRepo() throws MojoExecutionException {
        internalCreateRepo(true/*no update*/);
    }

    public File repoDataDirectory() {
        return new File(repositoryRoot, WellKnowns.YUM_REPODATA_FOLDERNAME);
    }

    /** Execute the createrepo command. */
    private void internalCreateRepo(boolean updateOnly) throws MojoExecutionException {
        Commandline commandline = new Commandline();
        commandline.setExecutable(this.createRepoCommand);
        ImmutableSet.Builder<String> args = ImmutableSet.<String>builder().addAll(createRepoArgs);
        if (updateOnly) {
            // if metadata already exists, we will execute "createrepo --update --skip-stat ."
            args.add("--update", "--skip-stat");
        }
        for (String arg : args.build()) {
            commandline.createArg().setValue(arg);
        }
        commandline.createArg().setValue(repositoryRoot.getPath());
        log.debug("Executing \'" + commandline.toString() + "\'");
        try {
            int result = CommandLineUtils.executeCommandLine(commandline, NullStreamConsumer.theInstance, new LogStreamConsumer(log));
            if (result != 0) {
                throw new MojoExecutionException(createRepoCommand + " returned: \'" + result + "\' executing \'" + commandline + "\'");
            }
        } catch (CommandLineException e) {
            throw new MojoExecutionException("Unable to execute: " + commandline, e);
        }
        log.info("Successfully built repo using directory: " + repositoryRoot.getPath());
    }

    private List<String> extractFileListFromPrimaryMetadataFile(Document primaryMetadataFile) throws MojoExecutionException {
        // we will return a list of *repo-relative* file paths
        List<String> retval = new ArrayList<String>();
        String rootNamespaceUri = determineRootNamespaceUri(primaryMetadataFile);
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(SimpleNamespaceResolver.forPrefixAndNamespace("common", rootNamespaceUri));
        NodeList repoRelativeFilePaths =
            (NodeList) evaluateXPathNodeSet(xpath, "//common:metadata/common:package/common:location/@href", primaryMetadataFile);
        for (int i = 0; i < repoRelativeFilePaths.getLength(); ++i) {
            retval.add(repoRelativeFilePaths.item(i).getNodeValue());
        }
        return retval;
    }

    /** Resolve repomd file (i.e., repodata/repomd.xml) file. */
    private File determineRepoMetadataFile() {
        // path to repomd.xml, e.g.
        return new File(repoDataDirectory(), WellKnowns.YUM_REPOMETADATA_FILENAME);
    }

    private File resolvePrimaryMetadataFile(Document metadata) throws MojoExecutionException {
        // determine root namespace for use in xpath queries
        String rootNamespaceUri = determineRootNamespaceUri(metadata);
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(SimpleNamespaceResolver.forPrefixAndNamespace("repo", rootNamespaceUri));
        // primary metadata file, relative to *repository* root
        String repoRelativePrimaryMetadataFilePath =
            evaluateXPathString(xpath, "//repo:repomd/repo:data[@type='primary']/repo:location/@href", metadata);
        // determine primary metadata file (typically "repodata/primary.xml.gz")
        File primaryMetadataFile = new File(repositoryRoot, repoRelativePrimaryMetadataFilePath);
        if (!primaryMetadataFile.isFile() || !primaryMetadataFile.getName().endsWith(".gz")) {
            throw new MojoExecutionException("Primary metadata file, '" + primaryMetadataFile.getPath() +
                "', does not exist or does not have .gz extension");
        }
        return primaryMetadataFile;
    }

    private static Object evaluateXPathNodeSet(XPath xpath, String expression, Document document) throws MojoExecutionException {
        try {
            return xpath.evaluate(expression, document, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new MojoExecutionException(expression, e);
        }
    }

    private static String evaluateXPathString(XPath xpath, String expression, Document document) throws MojoExecutionException {
        try {
            return xpath.evaluate(expression, document);
        } catch (XPathExpressionException e) {
            throw new MojoExecutionException(expression, e);
        }
    }

    private static String determineRootNamespaceUri(Document metadata) {
        return metadata.getChildNodes().item(0).getNamespaceURI();
    }

}
