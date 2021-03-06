package org.jvnet.ws.wadl.maven;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.ws.wadl2java.Wadl2Java;

/**
 * A Maven plugin to generate Java code from WADL descriptions.
 * 
 * @author Wilfred Springer
 * @goal generate
 * @phase generate-sources
 */
public class Wadl2JavaMojo extends AbstractMojo {

    /**
     * The packagename for classes generated by this Mojo.
     * 
     * @parameter
     * @required
     */
    private String packageName;

    /**
     * The target directory, to which all Java code will be generated.
     * 
     * @parameter expression="${basedir}/target/generated-sources/wadl"
     */
    private File targetDirectory;

    /**
     * The directory containing the WADL files.
     * 
     * @parameter expression="${basedir}/src/main/wadl"
     */
    private File sourceDirectory;

    /**
     * The patterns of the files to be included in the transformation.
     * 
     * @parameter expression="*.wadl"
     */
    private String includes;

    /**
     * The names of customization files.
     * 
     * @parameter
     */
    private List<String> customizations;

    /**
     * The current project.
     * 
     * @parameter expression="${project}"
     */
    private MavenProject project;

    /**
     * Autopackaging.
     * 
     * @parameter expression="${autoPackaging}"
     */
    private boolean autoPackaging = true;

    /**
     * A boolean, indicating if the mojo should fail entirely if it fails to
     * generate code from a single WADL file.
     * 
     * @parameter default="false"
     */
    private boolean failOnError;

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (sourceDirectory.exists() && sourceDirectory.canRead()) {
            assureTargetDirExistence();
            String[] matches = getWadlFileMatches();
            Wadl2Java processor = createProcessor();
            for (int i = matches.length - 1; i >= 0; i--) {
                File file = new File(sourceDirectory, matches[i]);
                try {
                    processor.process(file.toURI());
                } catch (Exception e) {
                    if (!failOnError) {
                        getLog().warn(
                                "Failed to generate code from "
                                        + file.getAbsolutePath(), e);
                    } else {
                        throw new MojoExecutionException(
                                "Failed to generate code from "
                                        + file.getAbsolutePath());
                    }
                }
            }
            project.addCompileSourceRoot(targetDirectory.getAbsolutePath());
        }
    }

    /**
     * Returns the WADL files to be processed.
     * 
     * @return The WADL files to be processed, based on the {@link #includes}
     *         Mojo parameter.
     */
    private String[] getWadlFileMatches() {
        String[] patterns = includes.split(",");
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(sourceDirectory);
        scanner.setIncludes(patterns);
        scanner.scan();
        String[] matches = scanner.getIncludedFiles();
        return matches;
    }

    /**
     * Create a new {@link Wadl2Java} processor, based on the Mojo parameters.
     * 
     * @return A new {@link Wadl2Java} instance.
     */
    private Wadl2Java createProcessor() {
        List<File> customizationFiles = new ArrayList<File>(customizations
                .size());
        for (String customization : customizations) {
            customizationFiles.add(new File(customization));
        }
        Wadl2Java processor = new Wadl2Java(targetDirectory, packageName,
                autoPackaging, customizationFiles);
        return processor;
    }

    /**
     * Verifies if the target directory exists, and if it doesn't exist, creates
     * it.
     * 
     * @throws MojoExecutionException
     *             If it failed to create the target directory.
     */
    private void assureTargetDirExistence() throws MojoExecutionException {
        if (!targetDirectory.exists()) {
            if (!targetDirectory.mkdirs()) {
                throw new MojoExecutionException("Failed to create "
                        + targetDirectory.getAbsolutePath());
            }
        }
    }

}
