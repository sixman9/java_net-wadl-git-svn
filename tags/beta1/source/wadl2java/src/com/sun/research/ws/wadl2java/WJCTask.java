/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the "License").  You may not use this file except
 * in compliance with the License.
 * 
 * You can obtain a copy of the license at
 * http://www.opensource.org/licenses/cddl1.php
 * See the License for the specific language governing
 * permissions and limitations under the License.
 */

/*
 * WJCTask.java
 *
 * Created on May 2, 2006, 11:37 AM
 *
 */

package com.sun.research.ws.wadl2java;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;

/**
 * Ant task implementation for the WADL to Java tool
 *
 *<p>Use as an ant task:</p>
 *
 * <pre> &lt;property name="jaxws.home" value="/path/to/jax-ws/directory" /&gt;
 * &lt;property name="wadl2java.home" value="/path/to/wadl2java/directory" /&gt;
 *
 * &lt;taskdef name="wjc" classname="com.sun.research.ws.wadl2java.WJCTask"&gt;
 *   &lt;classpath&gt;
 *     &lt;fileset dir="${jaxws.home}" includes="lib/*.jar" /&gt;
 *     &lt;pathelement location="${wadl2java.home}/dist/wadl2java.jar"/&gt;
 *   &lt;/classpath&gt;
 * &lt;/taskdef&gt;
 *
 * &lt;target name="-pre-compile"&gt;
 *   &lt;echo message="Compiling the description..." /&gt;
 *   &lt;wjc description="file.wadl" package="com.yahoo.search" target="gen-src"&gt;
 *     &lt;produces dir="gen-src/com/yahoo/search" includes="*.java"/&gt;
 *     &lt;depends dir="." includes="schema.xsd"/&gt;
 *   &lt;/wjc&gt;
 * &lt;/target&gt;</pre>

 * @author mh124079
 */
public class WJCTask extends Task {
    
    private String pkg;
    private File target;
    private URI desc;
    private List<FileSet> producedFileSets;
    private List<FileSet> consumedFileSets;
    
    /**
     * Default constructor for WJCTask
     */
    public WJCTask() {
    }
    
    /**
     * Set the package in which generates code will be placed. Equivalent to the
     * command line <code>-p package</code> option.
     * @param pkg the package in which to generate code, e.g. 'org.example.test'.
     */
    public void setPackage(String pkg) {
        this.pkg = pkg;
    }
    
    /**
     * Sets the WADL file to be processed.
     * @param desc the WADL file to be processed.
     */
    public void setDescription(URI desc) {
        this.desc = desc;
    }
    
    /**
     * Set the directory in which generates code will be placed. Equivalent to the
     * command line <code>-o directory</code> option.
     * @param target the directory in which generated code will be written. E.g. if <code>target</code>
     * is <code>gen-src</code> and <code>package</code> is <code>org.example.test</code>
     * then generated code will be written to <code>gen-src/org/example/test</code>.
     * <code>target</code> must exist, subdirectories will be created as required.
     */
    public void setTarget(File target) {
        this.target = target;
    }
    
    /**
     * Add a pre-configured FileSet for a <code>produces</code> child element.
     * The fileset defines a set of files produced by this task and is used 
     * in an up-to-date check when deciding if the WADL description should be
     * compiled or not.
     * @param fileset the pre-configured FileSet object
     */
    public void addConfiguredProduces(FileSet fileset) {
        producedFileSets.add(fileset);
    }

    /**
     * Add a pre-configured FileSet for a <code>depends</code> child element.
     * The fileset defines a set of files used by this task and is used 
     * in an up-to-date check when deciding if the WADL description should be
     * compiled or not. The description file is automatically included in the
     * up-to-date check and doesn't need to be specified separately.
     * @param fileset the pre-configured FileSet object
     */
    public void addConfiguredDepends(FileSet fileset) {
        consumedFileSets.add(fileset);
    }

    /**
     * Initializes the task ready to process a WADL file.
     * @throws org.apache.tools.ant.BuildException if an error occurs during initialization.
     */
    public void init() throws BuildException {
        super.init();
        pkg = null;
        target = null;
        desc = null;
        producedFileSets = new ArrayList<FileSet>();
        consumedFileSets = new ArrayList<FileSet>();
    }

    /**
     * Processes the previously set WADL file and generated code in the specified package and target directory.
     * @throws org.apache.tools.ant.BuildException if processing of the WADL file fails.
     */
    public void execute() throws BuildException {
        if (pkg == null)
            throw new BuildException("package attribute must be sepcified");
        if (target == null)
            throw new BuildException("target attribute must be specified");
        if (!target.exists())
            throw new BuildException("target directory ("+target.toString()+") must exist");
        if (!target.isDirectory())
            throw new BuildException("target attribute ("+target.toString()+") must specify a directory");
        if (desc == null)
            throw new BuildException("description attribute must be specified");
        
        if (desc.getScheme()==null || desc.getScheme().equals("file")) {
            // assume a file if not explicitly told otherwise
            File fileDesc = new File(this.getOwningTarget().getProject().getBaseDir(), desc.getPath());
            if (!fileDesc.exists())
                throw new BuildException("WADL description ("+desc.toString()+") must exist");
            if (!fileDesc.isFile())
                throw new BuildException("WADL description ("+desc.toString()+") must be a file");
            desc = fileDesc.toURI();

            // check if description has changed since code was last generated
            long earliestProducedFileStamp = Long.MAX_VALUE;
            for (FileSet fs: producedFileSets) {
                DirectoryScanner ds = fs.getDirectoryScanner(getProject());
                String[] includedFiles = ds.getIncludedFiles();
                for (String filename: includedFiles) {
                    File f = new File(ds.getBasedir(), filename);
                    if (f.lastModified() < earliestProducedFileStamp)
                        earliestProducedFileStamp = f.lastModified();
                }
            }
            long latestConsumedFileStamp = fileDesc.lastModified();
            for (FileSet fs: consumedFileSets) {
                DirectoryScanner ds = fs.getDirectoryScanner(getProject());
                String[] includedFiles = ds.getIncludedFiles();
                for (String filename: includedFiles) {
                    File f = new File(ds.getBasedir(), filename);
                    if (f.lastModified() > latestConsumedFileStamp)
                        latestConsumedFileStamp = f.lastModified();
                }
            }

            if (earliestProducedFileStamp < Long.MAX_VALUE && latestConsumedFileStamp < earliestProducedFileStamp) {
                log("Generated code is up to date, skipping compilation");
                return;
            }
        }
        
        // pre-requisites satisfied, compile the description
        try {
            Wadl2Java wadlProcessor = new Wadl2Java(target, pkg);
            wadlProcessor.process(desc);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new BuildException("WADL file processing failed", ex);
        }
    }
}
