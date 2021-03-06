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
 * Wadl2Java.java
 *
 * Created on May 11, 2006, 11:35 AM
 *
 */

package org.jvnet.ws.wadl2java;

import com.sun.codemodel.*;
import org.jvnet.ws.wadl.*;
import org.jvnet.ws.wadl2java.ast.FaultNode;
import org.jvnet.ws.wadl2java.ast.MethodNode;
import org.jvnet.ws.wadl2java.ast.RepresentationNode;
import org.jvnet.ws.wadl2java.ast.ResourceNode;
import org.jvnet.ws.wadl2java.ast.ResourceTypeNode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import com.sun.tools.xjc.api.S2JJAXBModel;
import com.sun.tools.xjc.api.ErrorListener;
import com.sun.tools.xjc.api.SchemaCompiler;
import com.sun.tools.xjc.api.impl.s2j.SchemaCompilerImpl;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * Processes a WADL file and generates client-side stubs for the resources and
 * methods described.
 * @author mh124079
 */
public class Wadl2Java {
    
    private File outputDir;
    private List<File> customizations;
    private String pkg;
    private JPackage jPkg;
    private S2JJAXBModel s2jModel;
    private JCodeModel codeModel;
    private ElementResolver idMap;
    private Map<String, ResourceTypeNode> ifaceMap;
    private List<String> processedDocs;
    private JavaDocUtil javaDoc;
    private Unmarshaller u;
    private SchemaCompiler s2j;
    private ErrorListener errorListener;
    private String generatedPackages = "";
    private boolean autoPackage;

    /**
     * Creates a new instance of a Wadl2Java processor.
     * @param outputDir the directory in which to generate code.
     * @param pkg the Java package in which to generate code.
     * @param autoPackage whether to use JAXB auto package name generation
     */
    public Wadl2Java(File outputDir, String pkg, boolean autoPackage) {
        this.outputDir = outputDir;
        this.pkg = pkg;
        this.javaDoc = new JavaDocUtil();
        this.processedDocs = new ArrayList<String>();
        this.customizations = new ArrayList<File>();
        this.autoPackage = autoPackage;
    }
    
    /**
     * Creates a new instance of a Wadl2Java processor.
     * @param outputDir the directory in which to generate code.
     * @param pkg the Java package in which to generate code.
     * @param autoPackage whether to use JAXB auto package name generation
     * @param customizations a list of JAXB customization files
     */
    public Wadl2Java(File outputDir, String pkg, boolean autoPackage, List<File> customizations) {
        this.outputDir = outputDir;
        this.pkg = pkg;
        this.javaDoc = new JavaDocUtil();
        this.processedDocs = new ArrayList<String>();
        this.customizations = customizations;
        this.autoPackage = autoPackage;
    }
    
    /**
     * Process the root WADL file and generate code.
     * @param rootDesc the URI of the WADL file to process
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid, a
     * referenced WADL file is invalid, or if the code generator encounters
     * a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     * @throws com.sun.codemodel.JClassAlreadyExistsException if, during code 
     * generation, the WADL processor attempts to create a duplicate
     * class. This indicates a structural problem with the WADL file, e.g. duplicate
     * peer resource entries.
     */
    public void process(URI rootDesc) throws JAXBException, IOException, 
            JClassAlreadyExistsException {
        // read in root WADL file
        JAXBContext jbc = JAXBContext.newInstance( "org.jvnet.ws.wadl", 
                this.getClass().getClassLoader() );
        u = jbc.createUnmarshaller();
        s2j = new SchemaCompilerImpl();
        errorListener = new SchemaCompilerErrorListener();
        if (!autoPackage)
            s2j.setDefaultPackageName(pkg);
        s2j.setErrorListener(errorListener);
        idMap = new ElementResolver();
        ifaceMap = new HashMap<String, ResourceTypeNode>();
        Application a = processDescription(rootDesc);
        List<ResourceNode> rs = buildAst(a, rootDesc);
        
        // generate code
        s2jModel = s2j.bind();
        if (s2jModel != null) {
            codeModel = s2jModel.generateCode(null, errorListener);
            Iterator<JPackage> packages = codeModel.packages();
            StringBuilder buf = new StringBuilder();
            while(packages.hasNext()) {
                JPackage genPkg = packages.next();
                if (!genPkg.isDefined("ObjectFactory"))
                    continue;
                if (buf.length() > 0)
                    buf.append(':');
                buf.append(genPkg.name());
            }
            generatedPackages = buf.toString();
            jPkg = codeModel._package(pkg);
            generateResourceTypeInterfaces();
            for (ResourceNode r: rs)
                generateEndpointClass(r);
            codeModel.build(outputDir);
        }
    }
    
    /**
     * Unmarshall a WADL file, process any schemas referenced in the WADL file, add 
     * any items with an ID to a global ID map, and follow any references to additional
     * WADL files.
     * @param desc the URI of the description file
     * @return the unmarshalled WADL application element
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    public Application processDescription(URI desc) 
            throws JAXBException, IOException {
        // check for files that have already been processed to prevent loops
        if (processedDocs.contains(desc.toString()))
            return null;
        processedDocs.add(desc.toString());
        
        // read in WADL file
        System.out.println(Wadl2JavaMessages.PROCESSING(desc.toString()));
        Application a = (Application)u.unmarshal(desc.toURL());
        
        // process embedded schemas
        Grammars g = a.getGrammars();
        if (g != null) {
            for (Include i: g.getInclude()) {
                URI incl = desc.resolve(i.getHref());
                if (processedDocs.contains(incl.toString()))
                    continue;
                processedDocs.add(incl.toString());
                System.out.println(Wadl2JavaMessages.PROCESSING(incl.toString()));
                InputSource input = new InputSource(incl.toURL().openStream());
                input.setSystemId(incl.toString());
                s2j.parseSchema(input);
            }
            int embeddedSchemaNo = 0; // used to generate unique system ID
            for (Object any: g.getAny()) {
                if (any instanceof Element) {
                    Element element = (Element)any;
                    s2j.parseSchema(desc.toString()+"#schema"+
                            Integer.toString(embeddedSchemaNo), element);
                    embeddedSchemaNo++;
                }
            }
        }
        for (File customization: customizations) {
            URI incl = desc.resolve(customization.toURI());
            System.out.println(Wadl2JavaMessages.PROCESSING(incl.toString()));
            InputSource input = new InputSource(incl.toURL().openStream());
            input.setSystemId(incl.toString());
            s2j.parseSchema(input);
        }
        buildIDMap(a, desc);
        return a;
    }

    /**
     * Build a map of all method, param, representation, fault and resource_type
     * elements that have an ID. These are used to dereference href values
     * when building the ast.
     * @param desc the URI of the WADL file being processed
     * @param a the root element of an unmarshalled WADL document
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
    */
    @SuppressWarnings("unchecked")
    protected void buildIDMap(Application a, URI desc) throws JAXBException, IOException {
        // process globally declared items
        for (Object child: a.getResourceTypeOrMethodOrRepresentation()) {
            if (child instanceof Method)
                extractMethodIds((Method)child, desc);
            else if (child instanceof ResourceType)
                extractResourceTypeIds((ResourceType)child, desc);
            else if (child instanceof Representation)
                extractRepresentationId((Representation)child, desc);
            else
                extractParamId((Param)child, desc);
        }
        
        // process resource hierarchy
        if (a.getResources() != null)
            for (Resources rs: a.getResources())
                for (Resource r: rs.getResource())
                    extractResourceIds(r, desc);
    }
    
    /**
     * Adds the object to the ID map if it is identified and process any file pointed
     * to by href.
     * @param desc The URI of the current file being processed, used when resolving relative paths in href
     * @param id The identifier of o or null if o isn't identified
     * @param href A link to a another element, the document in which the element resides will
     * be recursively processed
     * @param o The object that is being identified or from which the link occurs
     * @return a unique identifier for the element or null if not identified
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    protected String processIDHref(URI desc, String id, String href, Object o)
            throws JAXBException, IOException {
        String uniqueId = idMap.addReference(desc, id, o);
        if (href != null && href.startsWith("#") == false) {
            // if the href references another document then unmarshall it
            // and recursively scan it for id and idrefs
            processDescription(getReferencedFile(desc, href));
        }
        return uniqueId;
    }
    
    /**
     * Extract the id from a representation element and add to the
     * representation map.
     * @param file the URI of the current WADL file being processed
     * @param r the representation element
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    protected void extractRepresentationId(Representation r, URI file) throws JAXBException, IOException {
        processIDHref(file, r.getId(), r.getHref(), r);
        for (Param p: r.getParam())
            extractParamId(p, file);
    }
    
    /**
     * Extract the id from a param element and add to the
     * representation map.
     * @param file the URI of the current WADL file being processed
     * @param p the param element
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    protected void extractParamId(Param p, URI file) throws JAXBException, IOException {
        processIDHref(file, p.getId(), p.getHref(), p);
    }
    
    /**
     * Extract the id from a method element and add to the
     * method map. Also extract the ids from any contained representation or
     * fault elements.
     * @param file the URI of the current WADL file being processed
     * @param m the method element
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    protected void extractMethodIds(Method m, URI file) throws JAXBException, IOException {
        processIDHref(file, m.getId(), m.getHref(), m);

        if (m.getRequest() != null) {
            for (Param p: m.getRequest().getParam())
                extractParamId(p, file);
            for (Representation r: m.getRequest().getRepresentation())
                extractRepresentationId(r, file);
        }
        for (Response resp: m.getResponse()) {
            for (Param p: resp.getParam())
                extractParamId(p, file);
            for (Representation r: resp.getRepresentation()) {
                extractRepresentationId(r, file);
            }
        }
    }
    
    /**
     * Extract the id from a resource element and add to the
     * resource map then recurse into any contained resources.
     * Also extract the ids from any contained param, method and its
     * representation or fault elements.
     * @param file the URI of the current WADL file being processed
     * @param r the resource element
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    protected void extractResourceIds(Resource r, URI file) throws JAXBException, IOException {
        processIDHref(file, r.getId(), null, r);
        for (String type: r.getType()) {
            processIDHref(file, null, type, r);
        }
        for (Param p: r.getParam())
            extractParamId(p, file);
        for (Object child: r.getMethodOrResource()) {
            if (child instanceof Method)
                extractMethodIds((Method)child, file);
            else if (child instanceof Resource)
                extractResourceIds((Resource)child, file);
        }
    }
    
    /**
     * Extract the id from a resource_type element and add to the
     * resource map.
     * Also extract the ids from any contained method and its param,
     * representation or fault elements.
     * @param file the URI of the current WADL file being processed
     * @param r the resource_type element
     * @throws javax.xml.bind.JAXBException if the WADL file is invalid or if 
     * the code generator encounters a problem.
     * @throws java.io.IOException if the specified WADL file cannot be read.
     */
    protected void extractResourceTypeIds(ResourceType r, URI file) throws JAXBException, IOException {
        String id = processIDHref(file, r.getId(), null, r);
        if (id != null)
            ifaceMap.put(id, null);
        for (Param p: r.getParam())
            extractParamId(p, file);
        for (Object child: r.getMethodOrResource()) {
            if (child instanceof Method)
                extractMethodIds((Method)child, file);
            else if (child instanceof Resource)
                extractResourceIds((Resource)child, file);
        }
    }
    
    /**
     * Generate Java interfaces for WADL resource types
     * @throws com.sun.codemodel.JClassAlreadyExistsException if the interface to be generated already exists
     */
    protected void generateResourceTypeInterfaces() 
            throws JClassAlreadyExistsException {
        for (String id: ifaceMap.keySet()) {
            ResourceTypeNode n = ifaceMap.get(id);
            JDefinedClass iface = jPkg._class(JMod.PUBLIC, n.getClassName(), ClassType.INTERFACE);
            n.setGeneratedInterface(iface);
            javaDoc.generateClassDoc(n, iface);
            ResourceClassGenerator rcGen = new ResourceClassGenerator(s2jModel, 
                codeModel, jPkg, generatedPackages, javaDoc, iface);
            // generate Java methods for each resource method
            for (MethodNode m: n.getMethods()) {
                rcGen.generateMethodDecls(m, true);
            }
            // generate bean properties for matrix parameters
            for (Param p: n.getMatrixParams()) {
                rcGen.generateBeanProperty(iface, p, true);
            }
        }
    }
    
    /**
     * Create a class that acts as a container for a hierarchy
     * of static inner classes, one for each resource described by the WADL file.
     * @param root the resource element that corresponds to the root of the resource tree
     * @throws com.sun.codemodel.JClassAlreadyExistsException if, during code 
     * generation, the WADL processor attempts to create a duplicate
     * class. This indicates a structural problem with the WADL file, e.g. duplicate
     * peer resource entries.
     */
    protected void generateEndpointClass(ResourceNode root) 
            throws JClassAlreadyExistsException {
        JDefinedClass impl = jPkg._class(JMod.PUBLIC, root.getClassName());
        javaDoc.generateClassDoc(root, impl);
        for (ResourceNode r: root.getChildResources()) {
            generateSubClass(impl, r);
        }
    }
    
    /**
     * Creates an inner static class that represents a resource and its 
     * methods. Recurses the tree of child resources.
     * @param parent the outer class for the static inner class being 
     * generated. This can either be a top level class or a nested static 
     * inner class for a parent resource.
     * @param resource the WADL <code>resource</code> element being processed.
     * @throws com.sun.codemodel.JClassAlreadyExistsException if, during code 
     * generation, the WADL processor attempts to create a duplicate
     * class. This indicates a structural problem with the WADL file, 
     * e.g. duplicate peer resource entries.
     */
    protected void generateSubClass(JDefinedClass parent, ResourceNode resource) 
            throws JClassAlreadyExistsException {
        
        ResourceClassGenerator rcGen = new ResourceClassGenerator(s2jModel, 
            codeModel, jPkg, generatedPackages, javaDoc, resource);
        JDefinedClass impl = rcGen.generateClass(parent);
        
        // generate Java methods for each resource method
        for (MethodNode m: resource.getMethods()) {
            rcGen.generateMethodDecls(m, false);
        }

        // generate sub classes for each child resource
        for (ResourceNode r: resource.getChildResources()) {
            generateSubClass(impl, r);
        }
    }
    
    /**
     * Build an abstract tree from an unmarshalled WADL file
     * @param a the application element of the root WADL file
     * @param rootFile the URI of the root WADL file. Other WADL files might be
     * included by reference.
     * @return the resource elements that correspond to the roots of the resource trees
     */
    protected List<ResourceNode> buildAst(Application a, URI rootFile) {
        // process resource types in two steps:
        // (i) process resource types in terms of methods
        for (String ifaceId: ifaceMap.keySet()) {
            buildResourceType(ifaceId, a);
        }
        // (ii) process resource type child resources (which may reference
        // resource types located in (i)
        for (String ifaceId: ifaceMap.keySet()) {
            buildResourceTypeTree(ifaceId, a);
        }
        
        List<Resources> rs = a.getResources();
        List<ResourceNode> ns = new ArrayList<ResourceNode>();
        for (Resources r: rs) {
            ResourceNode rootResourcesNode = new ResourceNode(a, r);
            for (Resource child: r.getResource()) {
                buildResourceTree(rootResourcesNode, child, rootFile);
            }
            ns.add(rootResourcesNode);
        }
        
        return ns;
    }
    
    /**
     * Build an abstract resource type based on the methods of a resource type 
     * in a WADL file
     * @param ifaceId the identifier of the resource type
     * @param a the application element of the root WADL file
     */
    protected void buildResourceType(String ifaceId, Application a) {
        try {
            URI file = new URI(ifaceId.substring(0,ifaceId.indexOf('#')));
            ResourceType type = (ResourceType)idMap.get(ifaceId);
            ResourceTypeNode node = new ResourceTypeNode(type, file, idMap);
            for (Object child: type.getMethodOrResource()) {
                if (child instanceof Method)
                    addMethodToResourceType(node, (Method)child, file);
            }
            ifaceMap.put(ifaceId, node);
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        }        
    }
    
    /**
     * Build an abstract resource type tree based on the child resources of a 
     * resource type in a WADL file
     * @param ifaceId the identifier of the resource type
     * @param a the application element of the root WADL file
     */
    protected void buildResourceTypeTree(String ifaceId, Application a) {
        try {
            URI file = new URI(ifaceId.substring(0,ifaceId.indexOf('#')));
            ResourceType type = (ResourceType)idMap.get(ifaceId);
            ResourceTypeNode node = ifaceMap.get(ifaceId);
            for (Object child: type.getMethodOrResource()) {
                if (child instanceof Resource)
                    addResourceToResourceType(node, (Resource)child, file);
            }
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        }        
    }
    
    /**
     * Add a resource and (recursively) its children to a tree starting at the parent.
     * Follow references to resources across WADL file boundaries
     * @param parent the parent resource in the tree being built
     * @param resource the WADL resource to process
     * @param file the URI of the current WADL file being processed
     */
    protected void buildResourceTree(ResourceNode parent, 
            Resource resource, URI file) {
        if (resource != null) {
            ResourceNode n = parent.addChild(resource, file, idMap);
            for (String type: resource.getType()) {
                addTypeToResource(n, type, file);
            }
            for (Object child: resource.getMethodOrResource()) {
                if (child instanceof Resource) {
                    Resource childResource = (Resource)child;
                    buildResourceTree(n, childResource, file);
                } else if (child instanceof Method) {
                    Method m = (Method)child;
                    addMethodToResource(n, m, file);
                }
            }
        }
    }
    
    /**
     * Add a type to a resource.
     * Follow references to types across WADL file boundaries
     * @param href the identifier of the resource_type element to process
     * @param resource the resource
     * @param file the URI of the current WADL file being processed
     */
    protected void addTypeToResource(ResourceNode resource, String href, 
            URI file) {
        // dereference resource
        file = getReferencedFile(file, href);
        ResourceTypeNode n = ifaceMap.get(file.toString()+href.substring(href.indexOf('#')));
        
        if (n != null) {
            resource.addResourceType(n);
        } else {
            System.err.println(Wadl2JavaMessages.SKIPPING_REFERENCE(href, file.toString()));
        }  
    }
    
    /**
     * Add a method to a resource type.
     * Follow references to methods across WADL file boundaries
     * @param method the WADL method element to process
     * @param resource the resource type
     * @param file the URI of the current WADL file being processed
     */
    protected void addMethodToResourceType(ResourceTypeNode resource, Method method, 
            URI file) {
        String href = method.getHref();
        if (href != null && href.length() > 0) {
            // dereference resource
            file = getReferencedFile(file, href);
            method = idMap.resolve(file, href, Method.class);
        }
        if (method != null) {
            MethodNode n = new MethodNode(method, resource);
            Request request = method.getRequest();
            if (request != null) {
                for (Param p: request.getParam()) {
                    href=p.getHref();
                    if (href != null && href.length() > 0) {
                        // dereference param
                        file = getReferencedFile(file, href);
                        p = idMap.resolve(file, href, Param.class);
                    }
                    if (p != null)
                        n.getQueryParameters().add(p);
                }
                for (Representation r: request.getRepresentation()) {
                    addRepresentation(n.getSupportedInputs(), r, file);
                }
            }
            for (Response response: method.getResponse()) {
                boolean isFault = isFaultResponse(response);
                for (Representation o: response.getRepresentation()) {
                    if (isFault) {
                        FaultNode fn = new FaultNode(o);
                        n.getFaults().add(fn);
                    } else {
                        addRepresentation(n.getSupportedOutputs(), o, file);
                    }
                }
            }
        }        
    }
    
    /**
     * Add a child resource to a resource type.
     * Follow references to resources across WADL file boundaries
     * @param resource the WADL resource element to process
     * @param type the parent resource type
     * @param file the URI of the current WADL file being processed
     */
    protected void addResourceToResourceType(ResourceTypeNode type, Resource resource, 
            URI file) {
        if (resource != null) {
            ResourceNode n = type.addChild(resource, file, idMap);
            for (String resourceType: resource.getType()) {
                addTypeToResource(n, resourceType, file);
            }
            for (Object child: resource.getMethodOrResource()) {
                if (child instanceof Resource) {
                    Resource childResource = (Resource)child;
                    buildResourceTree(n, childResource, file);
                } else if (child instanceof Method) {
                    Method m = (Method)child;
                    addMethodToResource(n, m, file);
                }
            }
        }
    }
    
    /**
     * Check if the supplied Response represents an error or not. If any
     * of the possible HTTP status values is >= 400 the Response is considered
     * to represent a fault.
     * @param response the response to check
     * @return true if the response represents a fault, false otherwise.
     */
    boolean isFaultResponse(Response response) {
        boolean isFault = false;
        for (long status: response.getStatus()) {
            if (status >= 400) {
                isFault = true;
                break;
            }
        }
        return isFault;
    }
    
    /**
     * Add a method to a resource.
     * Follow references to methods across WADL file boundaries
     * @param method the WADL method element to process
     * @param resource the resource
     * @param file the URI of the current WADL file being processed
     */
    protected void addMethodToResource(ResourceNode resource, Method method, 
            URI file) {
        String href = method.getHref();
        if (href != null && href.length() > 0) {
            // dereference resource
            file = getReferencedFile(file, href);
            method = idMap.resolve(file, href, Method.class);
        }
        if (method != null) {
            MethodNode n = new MethodNode(method, resource);
            Request request = method.getRequest();
            if (request != null) {
                for (Param p: request.getParam()) {
                    href=p.getHref();
                    if (href != null && href.length() > 0) {
                        // dereference param
                        file = getReferencedFile(file, href);
                        p = idMap.resolve(file, href, Param.class);
                    }
                    if (p != null)
                        n.getQueryParameters().add(p);
                }
                for (Representation r: request.getRepresentation()) {
                    addRepresentation(n.getSupportedInputs(), r, file);
                }
            }
            for (Response response: method.getResponse()) {
                boolean isFault = isFaultResponse(response);
                for (Representation o: response.getRepresentation()) {
                    if (isFault) {
                        FaultNode fn = new FaultNode(o);
                        n.getFaults().add(fn);
                    } else {
                        addRepresentation(n.getSupportedOutputs(), o, file);
                    }
                }
            }
        }        
    }

    /**
     * Add a representation to a method's input or output list.
     * Follow references to representations across WADL file boundaries
     * @param list the list to add the representation to
     * @param representation the WADL representation element to process
     * @param file the URI of the current WADL file being processed
     */
    protected void addRepresentation(List<RepresentationNode> list, Representation representation, 
            URI file) {
        String href = representation.getHref();
        if (href != null && href.length() > 0) {
            // dereference resource
            file = getReferencedFile(file, href);
            representation = idMap.resolve(file, href, Representation.class);
        }
        if (representation != null) {
            RepresentationNode n = new RepresentationNode(representation);
            list.add(n);
        }
    }
    
    /**
     * Get the referenced file, currentFile will be returned if href is a
     * fragment identifier, otherwise href is resolved against currentFile.
     * @param currentFile the uri of the file that contains the reference, used 
     * to provide a base for relative paths
     * @param href the reference
     * @return the URI of the referenced file
     */
    protected static URI getReferencedFile(URI currentFile, String href) {
        if (href.startsWith("#"))
            return currentFile;
        // href references another file
        URI ref = currentFile.resolve(href.substring(0, href.indexOf('#')));
        return ref;
    }
    
    /**
     * Inner class implementing the JAXB <code>ErrorListener</code> interface to
     * support error reporting from the JAXB infrastructure.
     */
    protected class SchemaCompilerErrorListener implements ErrorListener {
        /**
         * Report a warning
         * @param sAXParseException the exception that caused the warning.
         */
        public void warning(SAXParseException sAXParseException) {
            System.err.println(Wadl2JavaMessages.WARNING(sAXParseException.getMessage()));
        }

        /**
         * Report informative message
         * @param sAXParseException the exception that caused the informative message.
         */
        public void info(SAXParseException sAXParseException) {
            System.err.println(Wadl2JavaMessages.INFO(sAXParseException.getMessage()));
        }

        /**
         * Report a fatal error
         * @param sAXParseException the exception that caused the fatal error.
         */
        public void fatalError(SAXParseException sAXParseException) {
            System.err.println(Wadl2JavaMessages.ERROR_FATAL(sAXParseException.getMessage()));
        }

        /**
         * Report an error.
         * @param sAXParseException the exception that caused the error.
         */
        public void error(SAXParseException sAXParseException) {
            System.err.println(Wadl2JavaMessages.ERROR(sAXParseException.getMessage()));
        }
        
    }

}
