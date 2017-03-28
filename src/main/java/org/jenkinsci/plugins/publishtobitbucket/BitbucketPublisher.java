package org.jenkinsci.plugins.publishtobitbucket;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.request.HttpRequestWithBody;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.queue.Tasks;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.GitURIRequirementsBuilder;
import org.kohsuke.stapler.*;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static hudson.model.queue.Tasks.getAuthenticationOf;
import static org.jenkinsci.plugins.publishtobitbucket.Utils.getExpandedVariable;

/**

 * @author Ali Khan
 */
public class BitbucketPublisher extends Builder implements SimpleBuildStep {
    private static final int ADD_HOOK=1;
    private static final int REMOVE_HOOK=2;
    private static final String REST_API="/rest/api/1.0";

    private  String serverUrl;
    private  String projectName;
    private  String ciServer;
    private  String credentialsId;
    private BProject createProject;
    private CIServer createJenkinsJobs;

    private String projectKey;
    private String repositoryUrl;
    private String repoName;
    private String localFolder;

    private Map<String,String> expandedVariables;
    private TaskListener currentListener;

    private transient StandardUsernamePasswordCredentials credentials;
    private static ObjectMapper mapper = new ObjectMapper() {
        private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper
                = new com.fasterxml.jackson.databind.ObjectMapper();

        public <T> T readValue(String value, Class<T> valueType) {
            try {
                return jacksonObjectMapper.readValue(value, valueType);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public String writeValue(Object value) {
            try {
                return jacksonObjectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    };
    @DataBoundConstructor
    public BitbucketPublisher(String serverUrl, String credentialsId, String projectKey,
                              BProject createProject,CIServer createJenkinsJobs ) {
        this.serverUrl = serverUrl;
        this.createProject = createProject;
        this.createJenkinsJobs = createJenkinsJobs;

        if (createProject != null) {
            this.projectName = createProject.projectName;
        }
        if (createJenkinsJobs != null) {
            this.ciServer = createJenkinsJobs.ciServer;
        }

        this.credentialsId = credentialsId;
        this.projectKey = projectKey;
        Unirest.setObjectMapper(mapper);
    }
    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @DataBoundSetter
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialId) {
        this.credentialsId = credentialId;
    }

    @DataBoundSetter
    public void setCreateProject(BProject createProject) {
        this.createProject = createProject;
    }

    @DataBoundSetter
    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    @DataBoundSetter
    public void setCreateJenkinsJobs(CIServer createJenkinsJobs) {
        this.createJenkinsJobs = createJenkinsJobs;
    }


    public String getServerUrl() {
        return serverUrl;
    }

    public String getProjectName() {
        return projectName;
    }
    public String getProjectUsers() {
        return this.createProject.projectUsers;
    }
    public String getProjectGroups() {
        return this.createProject.projectGroups;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getCiServer() {
        return ciServer;
    }

    public CIServer getCreateJenkinsJobs() {
        return createJenkinsJobs;
    }

    public String isCreateProjectEnabled(){
        return Strings.isNullOrEmpty(this.projectName)? "false":"true";
    }
    public String isCreateJenkinsJobs(){
        return Strings.isNullOrEmpty(this.ciServer)? "false":"true";
    }

    @Override
    public void perform(@Nonnull Run<?,?> build, @Nonnull FilePath workspace,
                        @Nonnull Launcher launcher, @Nonnull TaskListener listener) {
        try {
            this.currentListener = listener;
            this.expandedVariables = new HashMap<>();
            this.expandedVariables.put("projectKey",getExpandedVariable(this.projectKey,build,listener).toUpperCase());
            this.expandedVariables.put("projectName",getExpandedVariable(this.projectName,build,listener));
            this.expandedVariables.put("projectUsers",getExpandedVariable(this.createProject.projectUsers,build,listener));
            this.expandedVariables.put("projectGroups",getExpandedVariable(this.createProject.projectGroups,build,listener));
            this.expandedVariables.put("jenkinsServer",getExpandedVariable(this.ciServer,build,listener));
            Job<?, ?> job = build.getParent();
            if (job instanceof AbstractProject) {
                AbstractProject project = (AbstractProject) job;
                if (project.getScm() instanceof GitSCM){
                    GitSCM scm  = (GitSCM) project.getScm();
                    this.localFolder = scm.getRepositories().get(0).getURIs().get(0).toString();
                }
            }
            this.credentials=CredentialsProvider.findCredentialById(credentialsId, StandardUsernamePasswordCredentials.class, build);
            this.repoName = getRepoName(workspace,listener);
            if (repoName != null) {
                if (setupBitbucketProjectAndRepository()){
                    if (pushToGit()){
                        Thread.sleep(10000);
                        processHook(REMOVE_HOOK);
                    }
                }else{
                    throw new RuntimeException(Messages.BitbucketPublisher_projectNotCreated());

                }
            }else{
                throw new RuntimeException(Messages.BitbucketPublisher_pomNotFound());

            }
        } catch (XmlPullParserException | IOException | InterruptedException e) {
            throw new RuntimeException(Messages.BitbucketPublisher_generalError());

        }

    }
    private boolean setupBitbucketProjectAndRepository(){
        boolean createProject;
        createProject = !Strings.isNullOrEmpty(this.expandedVariables.get("projectName"));
        boolean createRepo = !createProject || createProject();
        boolean addHook = createRepo && createRepo();
        return addHook && processHook(ADD_HOOK);

    }

    private String getRepoName(FilePath workspace,TaskListener listener) throws IOException, XmlPullParserException, InterruptedException {
        if (workspace.exists()){
            File rootDir = new File(workspace.toURI());
            File pom = new File(rootDir.getPath()+"/pom.xml");
            if (pom.exists()){
                MavenXpp3Reader reader = new MavenXpp3Reader();
                Model model = reader.read(new InputStreamReader(new FileInputStream(pom), "UTF-8"));

                return model.getArtifactId();
            }else{
                listener.getLogger().println("POM not found inside."+rootDir.getPath());
            }

        }else{
            listener.getLogger().println("Root dir does not exist"+workspace.toURI());

        }
        return null;
    }

    private boolean createProject(){
        try {
            this.currentListener.getLogger().print("Creating Project :"+this.expandedVariables.get("projectName")+" with Key:"
                                                    +this.expandedVariables.get("projectKey"));
            BitbucketProject project = new BitbucketProject(this.expandedVariables.get("projectName"),this.expandedVariables.get("projectKey"));

            this.currentListener.getLogger().println(mapper.writeValue(project));
            Unirest.setObjectMapper(mapper);
            Unirest.setHttpClient(Utils.getClient());
            HttpResponse<String> response = Unirest.post(this.serverUrl + "/projects")
                    .header("accept", "application/json")
                    .header("Content-Type", "application/json")
                    .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                    .body(project).asString();
            this.currentListener.getLogger().println("Response : "+response.getBody());
            if (!response.getBody().contains("\"key\":\""+this.expandedVariables.get("projectKey")+"\"")){
                this.currentListener.getLogger().println("Unable to Create Project. Bitbucket Server Response :"+response.getBody());
                return false;
            }
            if (!Strings.isNullOrEmpty(this.expandedVariables.get("projectUsers"))) {
                String user_parameter = "name="+this.expandedVariables.get("projectUsers").replace(",","&name=");
                this.currentListener.getLogger().println("Request: "+this.serverUrl + "/projects/" + this.expandedVariables.get("projectKey")
                                                            + "/permissions/users?permission=PROJECT_WRITE&" +user_parameter);
                Unirest.put(this.serverUrl + "/projects/" + this.expandedVariables.get("projectKey")
                                + "/permissions/users?permission=PROJECT_WRITE&" +user_parameter)
                        .header("accept", "application/json")
                        .header("Content-Type", "application/json")
                        .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                        .body(project).asString();

            }
            if (!Strings.isNullOrEmpty(this.expandedVariables.get("projectGroups"))) {
                String group_parameter = "name="+this.expandedVariables.get("projectGroups").replace(",","&name=");
                this.currentListener.getLogger().println("Request: "+ this.expandedVariables.get("projectKey")
                                                        + "/permissions/groups?permission=PROJECT_WRITE&" +group_parameter);
                Unirest.put(this.serverUrl + "/projects/" + this.expandedVariables.get("projectKey")
                                + "/permissions/groups?permission=PROJECT_WRITE&" +group_parameter)
                        .header("accept", "application/json")
                        .header("Content-Type", "application/json")
                        .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                        .body(project).asString();

            }
            this.currentListener.getLogger().println("Successfully Created Project :"+this.expandedVariables.get("projectName")+" with Key:"
                                                        +this.expandedVariables.get("projectKey"));
            this.currentListener.getLogger().println("=======================");
            return true;
        } catch (UnirestException e) {
            this.currentListener.getLogger().println("ERROR: Creating Project:"+this.expandedVariables.get("projectName"));
            this.currentListener.getLogger().println(ExceptionUtils.getStackTrace(e));
            this.currentListener.getLogger().println(e.getMessage());
            return false;
        }

    }

    private boolean createRepo(){
        try {
            if ((Strings.isNullOrEmpty(repoName) || Strings.isNullOrEmpty(this.expandedVariables.get("projectKey")))) {
                this.currentListener.getLogger().println("No Project Key provided. Or Artifact Id is empty in pom.xml");
                return false;
            }
            String repoObj = "{ \"name\": \"" + repoName + "\"}";
            Unirest.setObjectMapper(mapper);
            Unirest.setHttpClient(Utils.getClient());
            HttpResponse<JsonNode> postResponse = Unirest.post(this.serverUrl + "/projects/" + this.expandedVariables.get("projectKey") + "/repos")
                    .header("accept", "application/json")
                    .header("Content-Type", "application/json")
                    .basicAuth(credentials.getUsername(),credentials.getPassword().getPlainText())
                    .body(repoObj).asJson();
            this.currentListener.getLogger().println(postResponse.getBody());
            org.json.JSONArray cloneurls = postResponse.getBody().getObject().getJSONObject("links").getJSONArray("clone");
            for (int i=0;i<cloneurls.length();i++){
                String href = cloneurls.getJSONObject(i).getString("href");
                if (href.startsWith("http")){
                    this.repositoryUrl = href;
                }
            }
            if (!Strings.isNullOrEmpty(this.expandedVariables.get("projectUsers"))) {
                String user_parameter = "name="+this.expandedVariables.get("projectUsers").replace(",","&name=");
                Unirest.put(this.serverUrl + "/projects/" + this.expandedVariables.get("projectKey") + "/repos/"+repoName
                                + "/permissions/users?permission=REPO_WRITE&" +user_parameter)
                        .header("accept", "application/json")
                        .header("Content-Type", "application/json")
                        .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText()).asString();


            }
            if (!Strings.isNullOrEmpty(this.expandedVariables.get("projectGroups"))) {
                String group_parameter = "name="+this.expandedVariables.get("projectGroups").replace(",","&name=");
                Unirest.put(this.serverUrl + "/projects/" + this.expandedVariables.get("projectKey")+ "/repos/"+repoName
                            +"/permissions/groups?permission=REPO_WRITE&" +group_parameter)
                        .header("accept", "application/json")
                        .header("Content-Type", "application/json")
                        .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText()).asString();

            }
            this.currentListener.getLogger().println("Repo URL="+this.repositoryUrl);
            return true;
        } catch (UnirestException e) {
            this.currentListener.getLogger().println(ExceptionUtils.getStackTrace(e));
            this.currentListener.getLogger().println(e.getMessage());
        }
        return false;
    }
    private void setupHook(HttpRequestWithBody request,String hookObj) throws UnirestException {
        request.header("accept", "application/json")
                .header("Content-Type", "application/json")
                .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                .body(hookObj).asString();
    }
    private boolean processHook(int command){

        String hookUrl = this.serverUrl+"/projects/"+this.expandedVariables.get("projectName")+"/repos/"+
                         this.repoName+"/settings/hooks/com.ngs.stash.externalhooks.external-hooks:external-post-receive-hook/enabled";
        String hookObj;
        Unirest.setHttpClient(Utils.getClient());
        if (!Strings.isNullOrEmpty(this.ciServer)) {
            try {
                switch (command) {
                    case ADD_HOOK: {
                        hookObj = "{\"exe\":\"create-jenkins-jobs.sh\",\"safe_path\":true,\"params\":\"" + credentials.getUsername()
                                    + "\\r\\n" + credentials.getPassword().getPlainText() + "\\r\\n" + this.expandedVariables.get("jenkinsServer")
                                    + "\"}";
                        setupHook(Unirest.put(hookUrl), hookObj);
                        break;
                    }
                    default:
                    case REMOVE_HOOK: {
                        hookObj = "{\"exe\":\"create-jenkins-jobs.sh\",\"safe_path\":true,\"params\":\"\"}";
                        setupHook(Unirest.put(hookUrl), hookObj);
                        setupHook(Unirest.delete(hookUrl), hookObj);
                        break;
                    }

                }
                return true;
            } catch (UnirestException e) {
                this.currentListener.getLogger().println(ExceptionUtils.getStackTrace(e));
                this.currentListener.getLogger().println(e.getMessage());
            }
        }
        return false;
    }


    private boolean pushToGit(){
        try {
            Git git = Git.with(this.currentListener, new EnvVars(EnvVars.masterEnvVars)).in(new File(this.localFolder));
            GitTool gitTool = GitTool.getDefaultInstallation();
            if (gitTool != null) {
                git.using(gitTool.getGitExe());
            }
            GitClient client = git.getClient();

            client.addDefaultCredentials(this.credentials);
            //client.checkout().branch("master").execute();
            client.setRemoteUrl("origin", this.repositoryUrl);
            client.push().ref("master").to(new URIish("origin")).force().execute();
        } catch (URISyntaxException | IOException | InterruptedException e) {
            e.printStackTrace();
            this.currentListener.getLogger().println(ExceptionUtils.getStackTrace(e));
            this.currentListener.getLogger().println(e.getMessage());
            return false;
        }
        return true;
    }
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }


    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public DescriptorImpl() {
            load();
        }



        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Publish to Bitbucket Server";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this,formData);
            save();
            return super.configure(req,formData);
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Project project, @QueryParameter String credentialsId,
                                                     @QueryParameter String serverUrl) {
            return new StandardListBoxModel()
                    .includeMatchingAs(
                            getAuthenticationOf(project),
                            project,
                            StandardUsernameCredentials.class,
                            GitURIRequirementsBuilder.fromUri(serverUrl).build(),
                            GitClient.CREDENTIALS_MATCHER)
                    .includeCurrentValue(credentialsId);


        }
        public FormValidation doTestConnection(@QueryParameter("serverUrl") final String serverUrl,
                                               @QueryParameter("credentialsId") final String credentialsId,
                                               @AncestorInPath Project project) throws IOException, ServletException {
            String apiEndPoint= serverUrl+REST_API+"/projects";
            try {
                StandardUsernamePasswordCredentials credentials=null;
                List<StandardUsernamePasswordCredentials> credentialsList = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,
                                                                                                                    project);
                for (StandardUsernamePasswordCredentials c:credentialsList){
                    if (c.getId().equals(credentialsId)){
                        credentials = c;
                        break;

                    }
                }
                if (credentials != null) {
                    Unirest.setHttpClient(Utils.getClient());
                    HttpResponse<JsonNode> response = Unirest.get(apiEndPoint).basicAuth(credentials.getUsername(),
                                                                credentials.getPassword().getPlainText()).asJson();
                    if (response.getBody().getObject().has("errors")) {
                        return FormValidation.error("Error : "+response.getBody().getObject().get("errors").toString());
                    }
                    return FormValidation.ok("Success");

                }
                return FormValidation.ok("Success");
            } catch (Exception e) {
                return FormValidation.error("Error : "+e.getMessage());
            }
        }

        public FormValidation doCheckServerUrl(@QueryParameter String serverUrl, @QueryParameter String credentialsId,
                                               @AncestorInPath Project project ) throws IOException, ServletException {
            if (Strings.isNullOrEmpty(serverUrl)) {
                return FormValidation.error(Messages.BitbucketPublisher_urlEmpty());
            }
            String trimmed = serverUrl.trim();
            if (!isValidUrl (trimmed)) {
                return FormValidation.error(Messages.BitbucketPublisher_urlInvalid());

            }
            if (Strings.isNullOrEmpty(serverUrl)) {
                return FormValidation.error(Messages.BitbucketPublisher_credentialsEmpty());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckProjectKey(@QueryParameter String projectKey) throws IOException, ServletException {
            if (Strings.isNullOrEmpty(projectKey)) {
                return FormValidation.error(Messages.BitbucketPublisher_projectKeyEmpty());
            }

            return FormValidation.ok();
        }



        /**
         * Determines whether the given string is a valid URL.
         *
         * @param input to check
         * @return {@code true} if the input string is a vlid URL, {@code false} otherwise.
         */
        private boolean isValidUrl(String input) {
            try {
                new URI(input.replaceFirst("^[a-zA-Z]+://", "http://")).toURL();
                return true;
            } catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
                return false;
            }
        }

    }
    public static class BProject {
        String projectName;
        String projectUsers;
        String projectGroups;
        @DataBoundConstructor
        public BProject(String projectName,String projectUsers, String projectGroups){
            this.projectName = projectName;
            this.projectUsers = projectUsers;
            this.projectGroups = projectGroups;
        }
    }
    public static class CIServer {
        String ciServer;
        @DataBoundConstructor
        public CIServer(String ciServer){
            this.ciServer = ciServer;
        }
    }
}

