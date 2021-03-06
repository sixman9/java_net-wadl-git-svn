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
 * Main.java
 *
 * Created on April 27, 2006, 2:42 PM
 *
 */

package com.sun.research.ws.wadl2java;

import com.sun.codemodel.*;
import java.io.IOException;
import java.net.URISyntaxException;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.net.URI;

/**
 * Command line support for WADL to Java tool
 *
 * <p>Usage:</p>
 *
 * <pre>java com.sun.research.ws.wadl2java.Main -p package -o directory file.wadl</pre>
 *
 * <p>where:</p>
 * 
 * <dl>
 * <dt><code>-p package</code></dt>
 * <dd>Specifies the package used for generated code, e.g. com.example.test</dd>
 * <dt><code>-o directory</code></dt>
 * <dd>Specifies the directory to which files will be written. E.g. if the package
 * is <code>com.example.test</code> and the directory is <code>gen-src</code> then
 * files will be written to <code>./gen-src/com/example/test</code>. The directory
 * <code>dir</code> must exist, subdirectories will be created as required.</dd>
 * <dt><code>file.wadl</code></dt>
 * <dd>The WADL file to process.</dd>
 * </dl>
 * @author mh124079
 */
public class Main {
    
    /**
     * Print out the usage message
     */
    protected static void printUsage() {
        System.err.println("Usage: wadl2java -o outputDir -p package file.wadl");
    }
    
    /**
     * Entry point for the command line WADL to Java tool.
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        if (args.length%2 != 1) {
            printUsage();
            System.exit(1);
        }
            
        try {
            int i=0;
            File outputDir = null;
            String pkg = null;
            while (i<args.length-2) {
                if (args[i].equals("-o"))
                    outputDir = new File(args[i+1]);
                else if (args[i].equals("-p"))
                    pkg = args[i+1];
                else {
                    System.err.println("Unknown option: "+args[i]);
                    printUsage();
                    System.exit(1);
                }
                i+=2;
            }
            URI wadlDesc = new URI(args[args.length-1]);
            if (wadlDesc.getScheme()==null || wadlDesc.getScheme().equals("file")) {
                // assume a file if not told otherwise
                File wadlFile = new File(wadlDesc.getPath());
                if (!wadlFile.exists() || !wadlFile.isFile()) {
                    System.err.println(wadlFile.getPath()+" is not a file");
                    printUsage();
                    System.exit(1);
                }
                if (!outputDir.exists() || !outputDir.isDirectory()) {
                    System.err.println(outputDir.getPath()+" is not a directory");
                    printUsage();
                    System.exit(1);
                }
                wadlDesc = wadlFile.toURI();
            }
            Wadl2Java w = new Wadl2Java(outputDir, pkg);
            w.process(wadlDesc);
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        } catch (JAXBException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        } catch (JClassAlreadyExistsException ex) {
            ex.printStackTrace();
        }
    }
    
}
