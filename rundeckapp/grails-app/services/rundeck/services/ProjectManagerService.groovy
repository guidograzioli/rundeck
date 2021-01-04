/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.services


import com.codahale.metrics.MetricRegistry
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.Authorization
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.common.IRundeckProjectConfig
import com.dtolabs.rundeck.core.common.ProjectManager
import com.dtolabs.rundeck.core.config.Features
import com.dtolabs.rundeck.core.storage.ResourceMeta
import com.dtolabs.rundeck.core.storage.StorageConverterPluginAdapter
import com.dtolabs.rundeck.core.storage.StorageTimestamperConverter
import com.dtolabs.rundeck.core.storage.StorageUtil
import com.dtolabs.rundeck.core.storage.keys.KeyStorageTree
import com.dtolabs.rundeck.core.storage.projects.ProjectStorageTree
import com.dtolabs.rundeck.core.utils.IPropertyLookup
import com.dtolabs.rundeck.core.utils.PropertyLookup
import com.dtolabs.rundeck.server.projects.ProjectFile
import com.dtolabs.rundeck.server.projects.ProjectInfo
import com.dtolabs.rundeck.server.projects.RundeckProject
import com.dtolabs.rundeck.server.projects.RundeckProjectConfig
import com.google.common.base.Optional
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import grails.compiler.GrailsCompileStatic
import grails.events.annotation.Subscriber
import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.apache.commons.fileupload.util.Streams
import org.rundeck.app.authorization.AppAuthContextProcessor
import org.rundeck.app.spi.RundeckSpiBaseServicesProvider
import org.rundeck.app.spi.Services
import org.rundeck.storage.api.PathUtil
import org.rundeck.storage.api.Resource
import org.rundeck.storage.conf.TreeBuilder
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import rundeck.Project
import rundeck.services.feature.FeatureService

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Transactional
@GrailsCompileStatic
class ProjectManagerService implements ProjectManager, ApplicationContextAware, InitializingBean {
    public static final String ETC_PROJECT_PROPERTIES_PATH = "/etc/project.properties"
    public static final String MIME_TYPE_PROJECT_PROPERTIES = 'text/x-java-properties'
    public static final String DEFAULT_PROJECT_CACHE_SPEC = "expireAfterAccess=10m,refreshAfterWrite=1m"
    public static final String DEFAULT_ACL_CACHE_SPEC = "refreshAfterWrite=2m"
    public static final String DEFAULT_FILE_CACHE_SPEC = "refreshAfterWrite=10m"
    def FrameworkService frameworkService
    ConfigStorageService configStorageService
    AuthorizationService authorizationService
    ApplicationContext applicationContext
    ConfigurationService configurationService
    def metricService
    def rundeckNodeService
    FeatureService featureService
    private StorageService storageService
    AppAuthContextProcessor rundeckAuthContextProcessor
    /**
     * Scheduled executor for retries
     */
    private ExecutorService executor = Executors.newFixedThreadPool(2)

    /**
     * Provides subtree access for the project without authorization
     * @param project
     * @param subpath
     * @return
     */
    public ProjectStorageTree nonAuthorizingProjectStorageTreeSubpath(String project, String subpath) {
        ProjectStorageTree.withTree(
            StorageUtil.asStorageTree(
                TreeBuilder.<ResourceMeta> builder().base(
                    configStorageService.storageTreeSubpath(
                        projectStorageSubpath(project, subpath)
                    )
                ).convert(
                    new StorageConverterPluginAdapter(
                        "builtin:timestamp",
                        new StorageTimestamperConverter()
                    )
                ).build()
            ),
            project
        )
    }

    String getProjectPluginStorageSubpath(String service, String provider){
        "plugins/${service}/${provider}"
    }

    private StorageService getStorageService(){
        if (null == storageService) {
            storageService = applicationContext.getBean("storageService", StorageService)
        }
        return storageService
    }

    KeyStorageTree getDefaultAuthorizingProjectServices(String project){
        String accessUsername = "system"
        String accessRole = "system"

        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForUserAndRolesAndProject(
                accessUsername,
                [accessRole],
                project
        )

        return getStorageService().storageTreeWithContext(authContext)
    }

    /**
     * Create a services provider for the config storage tree for the given project, accessing only the given path
     * @param project project name
     * @param subpath subpath
     * @return
     */
    Services getNonAuthorizingProjectServicesForPlugin(String project, String service, String provider) {
        getNonAuthorizingProjectServices(project,getProjectPluginStorageSubpath(service, provider))
    }

    /**
     * Create a services provider for the config storage tree for the given project, accessing only the given path
     * @param project project name
     * @param subpath subpath
     * @return
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    Services getNonAuthorizingProjectServices(String project, String subpath) {
        new RundeckSpiBaseServicesProvider(
            services: [
                (ProjectStorageTree): nonAuthorizingProjectStorageTreeSubpath(project, subpath),
                (KeyStorageTree): getDefaultAuthorizingProjectServices(project),
            ]
        )
    }
    @Override
    Collection<IRundeckProject> listFrameworkProjects() {
        return listFrameworkProjectNames().collect {
            getFrameworkProject(it)
        }
    }

    @Subscriber('rundeck.bootstrap')
    @CompileStatic
    void init() {
        if(!featureService.featurePresent(Features.PROJMGR_SVC_BOOTSTRAP_WARMUP_CACHE)){
            return
        }
        log.debug("init...")
        long now = System.currentTimeMillis()
        listFrameworkProjects()
        log.debug("init: listFrameworkProjects: ${System.currentTimeMillis() - now}")
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Collection<String> listFrameworkProjectNames() {
        def c = Project.createCriteria()
        c.list {
            projections {
                property "name"
            }
        }
    }
    @Override
    int countFrameworkProjects() {
        return Project.count()
    }
    IRundeckProject getFrameworkProject(final String name) {
        if (null==projectCache.getIfPresent(name) && !existsFrameworkProject(name)) {
            throw new IllegalArgumentException("Project does not exist: " + name)
        }
        def result = projectCache.get(name)
        if (!result) {
            throw new IllegalArgumentException("Project does not exist: " + name)
        }
        return result
    }

    @Override
    boolean existsFrameworkProject(final String project) {
        Project.withSession{
            Project.countByName(project) > 0
        }
    }

    @Override
    IRundeckProject createFrameworkProject(final String projectName) {
        return createFrameworkProject(projectName, new Properties())
    }

    //basic creation, created via spec string in afterPropertiesSet()
    def LoadingCache<String, IRundeckProject> projectCache =
            CacheBuilder.newBuilder()
                        .expireAfterAccess(10, TimeUnit.MINUTES)
                        .refreshAfterWrite(1, TimeUnit.MINUTES)
                        .build(
                    new CacheLoader<String, IRundeckProject>() {
                        public IRundeckProject load(String key) {
                            return loadProject(key);
                        }
                    }
            );

    @Override
    void afterPropertiesSet() throws Exception {
        def spec = configurationService?.getCacheSpecFor("projectManagerService", "projectCache", DEFAULT_PROJECT_CACHE_SPEC)?:DEFAULT_PROJECT_CACHE_SPEC

        log.debug("projectCache: creating from spec: ${spec}")

        projectCache = CacheBuilder.from(spec)
                                   .recordStats()
                                   .build(
                new CacheLoader<String, IRundeckProject>() {
                    public IRundeckProject load(String key) {
                        return loadProject(key);
                    }

                    @Override
                    ListenableFuture<IRundeckProject> reload(final String key, final IRundeckProject oldValue)
                            throws Exception
                    {
                        if (needsReload(oldValue)) {
                            ListenableFutureTask<IRundeckProject> task = ListenableFutureTask.create(
                                    new Callable<IRundeckProject>() {
                                        public IRundeckProject call() {
                                            return loadProject(key);
                                        }
                                    }
                            );
                            executor.execute(task);
                            return task;
                        } else {
                            return Futures.immediateFuture(oldValue)
                        }
                    }
                }
        )
        def spec3 = configurationService?.getCacheSpecFor("projectManagerService", "fileCache", DEFAULT_FILE_CACHE_SPEC)?:DEFAULT_FILE_CACHE_SPEC

        log.debug("fileCache: creating from spec: ${spec3}")
        //basic creation, created via spec string in afterPropertiesSet()
        fileCache =
                CacheBuilder.from(spec3)
                            .recordStats()
                            .build(
                        new CacheLoader<ProjectFile, Optional<String>>() {
                            public Optional<String> load(ProjectFile key) {
                                return Optional.fromNullable(readProjectFileResourceAsString(key.project,key.path));
                            }
                            @Override
                            ListenableFuture<Optional<String>> reload(final ProjectFile key, final Optional<String> oldValue)
                                    throws Exception
                            {
                                ListenableFutureTask<Optional<String>> task = ListenableFutureTask.create{
                                    log.debug("fileCache: reloading file "+key)
                                    return Optional.fromNullable(readProjectFileResourceAsString(key.project,key.path));
                                }
                                executor.execute(task);
                                return task;
                            }
                        }
                )
        addCacheMetrics()
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private addCacheMetrics(){
        MetricRegistry registry = metricService?.getMetricRegistry()
        Util.addCacheMetrics(this.class.name+".projectCache", registry, projectCache)
        Util.addCacheMetrics(this.class.name+".fileCache", registry, fileCache)
    }


    boolean existsProjectFileResource(String projectName, String path) {
        def storagePath = projectStorageSubpath(projectName, path)
        return configStorageService.existsFileResource(storagePath)
    }
    boolean existsProjectDirResource(String projectName, String path) {
        def storagePath = projectStorageSubpath(projectName, path)
        return configStorageService.existsDirResource(storagePath)
    }

    public String projectStorageSubpath(String projectName, String path) {
        "projects/" + projectName + (!path ? '' : (path.startsWith("/") ? path : "/${path}"))
    }

    Resource<ResourceMeta> getProjectFileResource(String projectName, String path) {
        def storagePath = projectStorageSubpath(projectName, path)
        if (!configStorageService.existsFileResource(storagePath)) {
            return null
        }
        configStorageService.getFileResource(storagePath)
    }
    long readProjectFileResource(String projectName, String path, OutputStream output) {
        def storagePath = projectStorageSubpath(projectName, path)
        def resource = configStorageService.getFileResource(storagePath)
        Streams.copy(resource.contents.inputStream,output,false)
    }
    /**
     * Read or load the cached contents of a project file
     * @param projectName
     * @param path
     * @return
     */
    String readCachedProjectFileAsAstring(String projectName, String path) {
        Optional<String> cached = fileCache.get(ProjectFile.of(projectName, path))
        return cached.isPresent() ? cached.get() : null
    }
    /**
     * Read file contents as a string, or return null if not found
     * @param projectName
     * @param path
     * @return contents as string or null
     */
    String readProjectFileResourceAsString(String projectName, String path){
        if (!existsProjectFileResource(projectName, path)) {
            return null
        }
        def baos = new ByteArrayOutputStream()
        def len = readProjectFileResource(projectName, path, baos)
        return baos.toString()
    }
    static String rewritePrefix(String project, String respath){
        def prefix = 'projects/' + project+'/'
        return respath.startsWith(prefix)?respath.substring(prefix.length()):respath
    }
    /**
     * List the full paths of file resources in the directory at the given path
     * @param projectName projectname
     * @param path path directory path
     * @param pattern pattern match
     * @return
     */
    List<String> listProjectDirPaths(String projectName, String path, String pattern=null) {
        def prefix = 'projects/' + projectName
        def storagePath = prefix + (path.startsWith("/")?path:"/${path}")
        List<String> resources = []
        if(configStorageService.existsDirResource(storagePath)){
            resources = configStorageService.listDirPaths(storagePath, pattern)
        }
        resources.collect{String res->
            rewritePrefix(projectName, res)
        }.findAll{it}
    }
    /**
     * Update existing resource, fails if it does not exist
     * @param projectName project
     * @param path path
     * @param input stream
     * @param meta metadata
     * @return resource
     */
    Resource<ResourceMeta> updateProjectFileResource(String projectName, String path, InputStream input, Map<String,String> meta) {
        def storagePath = projectStorageSubpath(projectName, path)
        def res = configStorageService.updateFileResource(storagePath, input, meta)
        res
    }
    /**
     * Create new resource, fails if it exists
     * @param projectName project
     * @param path path
     * @param input stream
     * @param meta metadata
     * @return resource
     */
    Resource<ResourceMeta> createProjectFileResource(String projectName, String path, InputStream input, Map<String,String> meta) {
        def storagePath = projectStorageSubpath(projectName, path)

        def res = configStorageService.createFileResource(storagePath, input, meta)
        res
    }
    /**
     * Write to a resource, create if it does not exist
     * @param projectName project
     * @param path path
     * @param input stream
     * @param meta metadata
     * @return resource
     */
    Resource<ResourceMeta> writeProjectFileResource(String projectName, String path, InputStream input, Map<String,String> meta) {
        def storagePath = projectStorageSubpath(projectName, path)
        fileCache.invalidate(ProjectFile.of(projectName, path))
        if (!configStorageService.existsFileResource(storagePath)) {
            return createProjectFileResource(projectName, path, input, meta)
        }else{
            return updateProjectFileResource(projectName, path, input, meta)
        }
    }
    /**
     * delete a resource
     * @param projectName project
     * @param path path
     * @return true if file was deleted or does not exist
     */
    boolean deleteProjectFileResource(String projectName, String path) {
        def storagePath = projectStorageSubpath(projectName, path)
        fileCache.invalidate(ProjectFile.of(projectName, path))
        if (!configStorageService.existsFileResource(storagePath)) {
            return true
        }else{
            return configStorageService.deleteFileResource(storagePath)
        }
    }
    /**
     * delete all project file resources
     * @param projectName project
     * @return true if all files were deleted, false otherwise
     */
    boolean deleteAllProjectFileResources(String projectName) {
        def storagePath = "projects/" + projectName
        return configStorageService.deleteAllFileResources(storagePath)
    }

    Date getProjectConfigLastModified(String projectName) {
        def resource = getProjectFileResource(projectName, ETC_PROJECT_PROPERTIES_PATH)
        if(null==resource){
            return null
        }

        resource.getContents().modificationTime
    }
    boolean isValidConfigFile(byte[] bytes){
        def test = '#' + MIME_TYPE_PROJECT_PROPERTIES
        //validate header
        def validate=bytes.length>=test.length()?new String(bytes,0,test.length(),'ISO-8859-1'):null
        return test==validate
    }
    static class LoadedConfig{
        Properties config
        Date lastModified
        Date creationTime
    }
    private LoadedConfig loadProjectConfigResource(String projectName) {
        def resource = getProjectFileResource(projectName,ETC_PROJECT_PROPERTIES_PATH)
        if (null==resource) {
            return new LoadedConfig()
        }
        def properties = new Properties()
        def bytestream = new ByteArrayOutputStream()
        Streams.copy(resource.contents.inputStream,bytestream,true)
        def bytes=bytestream.toByteArray()
        //validate header
        if(isValidConfigFile(bytes)){
            //load as properties file
            try {
                properties.load(new ByteArrayInputStream(bytes))
            } catch (IOException e) {
                //TODO: throw exception?
    //            throw new RuntimeException("Failed loading project properties from storage: ${resource.path}: " + e.message)
                log.error("Failed loading project properties from storage: ${resource.path}: " + e.message,e)
            }
        }else{
            log.error("Failed loading project properties from storage: ${resource.path}: could not validate contents")
        }

        return new LoadedConfig(
                config      : properties,
                lastModified: resource.contents.modificationTime,
                creationTime: resource.contents.creationTime
        )
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private Map storeProjectConfig(String projectName, Properties properties) {
        def storagePath = ETC_PROJECT_PROPERTIES_PATH
        def baos = new ByteArrayOutputStream()
        properties['project.name']=projectName
        properties.store(baos, MIME_TYPE_PROJECT_PROPERTIES+";name=" + projectName)
        def bais = new ByteArrayInputStream(baos.toByteArray())

        def metadata = [(StorageUtil.RES_META_RUNDECK_CONTENT_TYPE):MIME_TYPE_PROJECT_PROPERTIES]
        def resource = writeProjectFileResource(projectName, storagePath, bais, metadata)

        projectCache.invalidate(projectName)
        rundeckNodeService.refreshProjectNodes(projectName)
        return [
                config      : properties,
                lastModified: resource.contents.modificationTime,
                creationTime: resource.contents.creationTime
        ]
    }
    @CompileStatic(TypeCheckingMode.SKIP)
    private void deleteProjectResources(String projectName) {
        if (!deleteAllProjectFileResources(projectName)) {
            log.error("Failed to delete all associated resources for project ${projectName}")
        }
        projectCache.invalidate(projectName)
        rundeckNodeService.refreshProjectNodes(projectName)
    }

    private IPropertyLookup createProjectPropertyLookup(String projectName, Properties config) {
        final Properties ownProps = new Properties();
        ownProps.setProperty("project.name", projectName);
        def create = PropertyLookup.create(
                createDirectProjectPropertyLookup(projectName,config),
                frameworkService.getRundeckFramework().propertyLookup
        )

        create.expand()
        return create
    }
    private static IPropertyLookup createDirectProjectPropertyLookup(String projectName, Properties config) {
        final Properties ownProps = new Properties();
        ownProps.setProperty("project.name", projectName);
        ownProps.putAll(config)
        def create = PropertyLookup.create(ownProps)
        create.expand()
        return create
    }
    public static final Map<String, String> DEFAULT_PROJ_PROPS = Collections.unmodifiableMap(
        [
            'resources.source.1.type'              : 'local',
            'service.NodeExecutor.default.provider': 'jsch-ssh',
            'service.FileCopier.default.provider'  : 'jsch-scp',
            'project.ssh-keypath'                  :
                new File(System.getProperty("user.home"), ".ssh/id_rsa").getAbsolutePath(),
            'project.ssh-authentication'           : 'privateKey'
        ]
    )

    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    IRundeckProject createFrameworkProject(final String projectName, final Properties properties) {
        Project found = Project.findByName(projectName)
        def description = properties.get('project.description')
        boolean generateInitProps = false
        if (!found) {
            def project = new Project(name: projectName, description: description)
            project.save(failOnError: true)
            generateInitProps = true
        }
        Properties storedProps = new Properties()
        storedProps.putAll(properties)
        if (generateInitProps) {
            Properties newProps = new Properties()
            DEFAULT_PROJ_PROPS.each { k, v ->
                if (null == properties || !properties.containsKey(k)) {
                    newProps.setProperty(k, v);
                }
            }
            storedProps.putAll(newProps)
        }
        def res = storeProjectConfig(projectName, storedProps)
        def rdprojectconfig = new RundeckProjectConfig(projectName,
                                                       createProjectPropertyLookup(projectName, res.config ?: new Properties()),
                                                       createDirectProjectPropertyLookup(projectName, res.config ?: new Properties()),
                                                       res.lastModified, res.creationTime
        )

        def newproj= preloadedProject(projectName, rdprojectconfig)
        newproj.info = new ProjectInfo(
                projectName: projectName,
                projectService: this,
                description: description? description: null
        )
        newproj.nodesFactory = rundeckNodeService
        return newproj
    }

    @Override
    void removeFrameworkProject(final String projectName) {
        Project found = Project.findByName(projectName)
        if (!found) {
            throw new IllegalArgumentException("project does not exist: " + projectName)
        }
        found.delete(flush: true)
        deleteProjectResources(projectName)
    }

    @Override
    IRundeckProject createFrameworkProjectStrict(final String projectName, final Properties properties) {
        Project found = Project.findByName(projectName)
        if (found) {
            throw new IllegalArgumentException("project exists: " + projectName)
        }
        return createFrameworkProject(projectName, properties)
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    void mergeProjectProperties(
            final RundeckProject project,
            final Properties properties,
            final Set<String> removePrefixes
    )
    {
        if(properties['project.description'] != null ) {
            def description = properties['project.description']
            Project.withSession {
                def dbproj = Project.findByName(project.name)
                dbproj.description = description ? description : null
                dbproj.save(flush: true)

            }
        }
        def resource=mergeProjectProperties(project.name,properties,removePrefixes)
        def rdprojectconfig = new RundeckProjectConfig(project.name,
                                                       createProjectPropertyLookup(project.name, resource.config ?: new Properties()),
                                                       createDirectProjectPropertyLookup(project.name, resource.config ?: new Properties()),
                                                       resource.lastModified,
                                                        resource.creationTime
        )
        project.projectConfig=rdprojectconfig
        project.nodesFactory = rundeckNodeService
    }

    Map mergeProjectProperties(
            final String projectName,
            final Properties properties,
            final Set<String> removePrefixes
    )
    {
        Project found = Project.findByName(projectName)
        if (!found) {
            throw new IllegalArgumentException("project does not exist: " + projectName)
        }
        def res = loadProjectConfigResource(projectName)
        Properties oldprops = res?.config?:new Properties()
        Properties newprops = mergeProperties(removePrefixes, oldprops, properties)
        Map newres=storeProjectConfig(projectName, newprops)
        newres
    }

    /**
     * Merge input properties with old properties, and remove any old properties with any of the given prefixes
     * @param removePrefixes prefix set
     * @param oldprops old properties
     * @param inProps input properties
     * @return merged properties
     */
    static Properties mergeProperties(Set<String> removePrefixes, Properties oldprops, Properties inProps) {
        def newprops = new Properties()
        if (removePrefixes) {
            oldprops.propertyNames().each { String k ->
                if (!removePrefixes.find { k.startsWith(it) }) {
                    newprops.put(k, oldprops.getProperty(k))
                }
            }
        }else{
            newprops.putAll(oldprops)
        }
        newprops.putAll(inProps)
        newprops
    }
    @CompileStatic(TypeCheckingMode.SKIP)
    void setProjectProperties(final RundeckProject project, final Properties properties) {
        def resource=setProjectProperties(project.name,properties)
        def rdprojectconfig = new RundeckProjectConfig(project.name,
                                                       createProjectPropertyLookup(project.name, resource.config ?: new Properties()),
                                                       createDirectProjectPropertyLookup(project.name, resource.config ?: new Properties()),
                                                       resource.lastModified,
                resource.creationTime
        )
        project.projectConfig=rdprojectconfig
        project.nodesFactory = rundeckNodeService
    }
    Map setProjectProperties(final String projectName, final Properties properties) {
        def description = properties['project.description']
        Project.withSession{
            def found = Project.findByName(projectName)
            if (!found) {
                throw new IllegalArgumentException("project does not exist: " + projectName)
            }
            found.description = description?description:null
            found.save(flush: true)

        }
        Map resource=storeProjectConfig(projectName, properties)
        resource
    }


    /**
     * Load the project config and node support
     * @param project
     * @return
     */
    IRundeckProjectConfig loadProjectConfig(final String project) {
        def resource = loadProjectConfigResource(project)
        def rdprojectconfig = new RundeckProjectConfig(project,
                                                 createProjectPropertyLookup(
                                                         project,
                                                         resource.config ?: new Properties()
                                                 ),
                                                 createDirectProjectPropertyLookup(
                                                         project,
                                                         resource.config ?: new Properties()
                                                 ),
                                                 resource.lastModified,
                resource.creationTime
        )
        return rdprojectconfig
    }
    RundeckProject preloadedProject(String project, IRundeckProjectConfig config){
        new RundeckProject(project, config, this)
    }
    RundeckProject lazyProject(String project){
        new RundeckProject(project,null, this)
    }
    /**
     * Load the project config and node support
     * @param project
     * @return
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    IRundeckProject loadProject(final String project) {
        if (!existsFrameworkProject(project)) {
            return null
        }
        long start=System.currentTimeMillis()
        log.info("Loading project definition for ${project}...")
        def rdproject = lazyProject(project)
        def description = getProjectDescription(project)
        //preload cached readme/motd
//        String readme = readCachedProjectFileAsAstring(project,"readme.md")
//        String motd = readCachedProjectFileAsAstring(project,"motd.md")
        rdproject.info = new ProjectInfo(
                projectName: project,
                projectService: this,
                description: description? description : null
        )
        rdproject.nodesFactory = rundeckNodeService
        log.info("Loaded project ${project} in ${System.currentTimeMillis()-start}ms")

        rundeckNodeService.refreshProjectNodes(project)
        return rdproject
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    def String getProjectDescription(String name){
        def c = Project.createCriteria()
        c.get {
            eq('name', name)
            projections {
                property "description"
            }
        }
    }
    boolean needsReload(IRundeckProject project) {
        Project.withSession {
            Project rdproject = Project.findByName(project.name)
            boolean needsReload = rdproject == null ||
                    project.configLastModifiedTime == null ||
                    getProjectConfigLastModified(project.name) > project.configLastModifiedTime
            needsReload
        }
    }

    //basic creation, created via spec string in afterPropertiesSet()
    private LoadingCache<ProjectFile, Optional<String>> fileCache =
            CacheBuilder.newBuilder()
                        .refreshAfterWrite(5, TimeUnit.MINUTES)
                        .build(
                    new CacheLoader<ProjectFile, Optional<String>>() {
                        public Optional<String> load(ProjectFile key) {
                            return Optional.fromNullable(readProjectFileResourceAsString(key.project,key.path));
                        }
                    }
            );


    /**
     * Mark imported file
     * @param source
     */
    void markProjectFileAsImported(IRundeckProject other, String path){
        //mark as imported
       try {
            def baos = new ByteArrayOutputStream()
            other.loadFileResource(path, baos)
            other.storeFileResource(
                    "${path}.imported",
                    new ByteArrayInputStream(baos.toByteArray())
            )
            other.deleteFileResource(path)
            log.warn("Filesystem project ${other.name}, marked as imported. Rename $path to ${path}.imported")
        } catch (IOException e) {
            log.error(
                    "Failed marking ${other.name} as imported (rename $path to ${path}.imported): ${e.message}",
                    e
            )
        }
    }
    /**
     * Import any projects that do not exist from the source
     * @param source
     */
    public void importProjectsFromProjectManager(ProjectManager source){
        source.listFrameworkProjects().each{IRundeckProject other->
            if(other.existsFileResource("etc/project.properties.imported")){
                //marked as imported, so skip re-import.
                log.warn("Discovered filesystem project ${other.name}, was previously imported.")
                return
            }
            boolean needsImport = configurationService?.getString('projectsStorageImportResources') == 'always'
            if(!existsFrameworkProject(other.name)){
                log.warn("Discovered filesystem project ${other.name}, importing...")
                def projectProps=new Properties()
                projectProps.putAll(other.getProjectProperties())
                def newProj = createFrameworkProject(other.name, projectProps)
                needsImport=true
            }else{
                log.warn("Skipping creation for filesystem project ${other.name}, it already exists.")
            }
            //mark as imported
            markProjectFileAsImported(other,"etc/project.properties")
            if(needsImport){
                log.warn("Importing resources for filesystem project: ${other.name} ...")
                def newProj=getFrameworkProject(other.name)
                //import resources
                int count=0
                List paths=other.listDirPaths('')
                while(paths.size()>0) {
                    String path = paths.remove(0)
                    if(path=="/etc/project.properties"){
                        continue
                    }
                    if(path.endsWith('.imported')){
                        continue
                    }
                    if(path.endsWith('/')){
                        paths.addAll(other.listDirPaths(path))
                    }else{
                        log.warn("Importing ${path} for project ${other.name}...")
                        def baos=new ByteArrayOutputStream()
                        try {
                            other.loadFileResource(path, baos)
                            def data = baos.toByteArray()
                            newProj.storeFileResource(path, new ByteArrayInputStream(data))
                            other.storeFileResource("${path}.imported", new ByteArrayInputStream(data))
                            other.deleteFileResource(path)
                            count++
                        }catch (IOException e){
                            log.error("Failed importing ${path} for project ${other.name}: ${e.message}",e)
                        }
                    }
                }
                log.warn("Imported ${count} resources for project: ${other.name}")
            }
        }
    }
}
