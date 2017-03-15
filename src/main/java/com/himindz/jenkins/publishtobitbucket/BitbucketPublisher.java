package com.himindz.jenkins.publishtobitbucket;

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
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.GitURIRequirementsBuilder;
import org.kohsuke.stapler.*;

import javax.servlet.ServletException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**

 * @author Ali Khan
 */
public class BitbucketPublisher extends Builder implements SimpleBuildStep {
    public static final int ADD_HOOK=1;
    public static final int REMOVE_HOOK=2;

    private  String serverUrl;
    private  String projectName;
    private  String credentialsId;
    private BProject createProject;
    private String projectKey;
    private String repositoryUrl;
    private String repoName;
    private String localFolder;

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
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public BitbucketPublisher(String serverUrl, String credentialsId, String projectKey,
                              BProject createProject) {
        this.serverUrl = serverUrl;
        this.createProject = createProject;
        if (createProject != null) {
            this.projectName = createProject.projectName;
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


    public String getServerUrl() {
        return serverUrl;
    }
    public String getProjectName() {
        return projectName;
    }
    public String getCredentialsId() {
        return credentialsId;
    }
    public BProject getCreateProject() {
        return createProject;
    }
    public String getProjectKey() {
        return projectKey;
    }


    public String isCreateProjectEnabled(){
        return Strings.isNullOrEmpty(this.projectName)? "false":"true";
    }

    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) {
        try {
            String pKey = Utils.getExpandedVariable(this.projectKey,build,listener);
            String pName = Utils.getExpandedVariable(this.projectName,build,listener);

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
                if (setupBitbucketProjectAndRepository(pKey,pName,listener)){
                    if (pushToGit(listener,workspace)){
                        processHook(REMOVE_HOOK,pKey,listener);
                    };
                }
            }
        } catch (XmlPullParserException | IOException | InterruptedException e) {
            e.printStackTrace();
        }

    }
    private boolean setupBitbucketProjectAndRepository(String pKey,String pName,TaskListener listener){
        boolean createProject;
        createProject = Strings.isNullOrEmpty(pName) ? false : true;
        boolean createRepo = !createProject || createProject(pKey,pName,listener);
        boolean addHook = createRepo && createRepo(pKey,repoName, listener);
        return addHook && processHook(ADD_HOOK,pKey,listener);

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

    private boolean createProject(String pKey, String pName,TaskListener listener){
        try {
            listener.getLogger().print("Creating Project :"+pName+" with Key:"+pKey);
            BitbucketProject project = new BitbucketProject(pName,pKey);
            listener.getLogger().println(mapper.writeValue(project));
            Unirest.setObjectMapper(mapper);
            Unirest.setHttpClient(Utils.getClient());
            Unirest.post(this.serverUrl + "/projects")
                    .header("accept", "application/json")
                    .header("Content-Type", "application/json")
                    .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                    .body(project).asString();
            listener.getLogger().println("Successfully Created Project :"+pName+" with Key:"+pKey);
            listener.getLogger().println("=======================");
            return true;
        } catch (UnirestException e) {
            listener.getLogger().println("ERROR: Creating Project:"+pName);
            listener.getLogger().println(ExceptionUtils.getStackTrace(e));
            listener.getLogger().println(e.getMessage());
            listener.getLogger().println("=======================");

            return false;
        }

    }

    private boolean createRepo(String pKey,String repoName, TaskListener listener){
        try {
            if ((Strings.isNullOrEmpty(repoName) || Strings.isNullOrEmpty(pKey))) {
                listener.getLogger().println("No Project Key provided. Or Repository Name is empty in pom.xml");
                return false;
            }
            String repoObj = "{ \"name\": \"" + repoName + "\"}";
            Unirest.setObjectMapper(mapper);
            Unirest.setHttpClient(Utils.getClient());
            HttpResponse<JsonNode> postResponse = Unirest.post(this.serverUrl + "/projects/" + pKey + "/repos")
                    .header("accept", "application/json")
                    .header("Content-Type", "application/json")
                    .basicAuth(credentials.getUsername(),credentials.getPassword().getPlainText())
                    .body(repoObj).asJson();
            listener.getLogger().println(postResponse.getBody());
            org.json.JSONArray cloneurls = postResponse.getBody().getObject().getJSONObject("links").getJSONArray("clone");
            for (int i=0;i<cloneurls.length();i++){
                String href = cloneurls.getJSONObject(i).getString("href");
                if (href.startsWith("http")){
                    this.repositoryUrl = href;
                }
            }
            listener.getLogger().println("Repo URL="+this.repositoryUrl);
            return true;
        } catch (UnirestException e) {
            e.printStackTrace();
            listener.getLogger().println(ExceptionUtils.getStackTrace(e));
            listener.getLogger().println(e.getMessage());
        }
        return false;
    }
    private void setupHook(HttpRequestWithBody request,String hookObj,TaskListener listener ) throws UnirestException {
        request.header("accept", "application/json")
                .header("Content-Type", "application/json")
                .basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                .body(hookObj).asString();
    }
    private boolean processHook(int command,String pKey,TaskListener listener){
        String hookUrl = this.serverUrl+"/projects/"+pKey+"/repos/"+this.repoName+"/settings/hooks/com.ngs.stash.externalhooks.external-hooks:external-post-receive-hook/enabled";
        String hookObj = null;
        Unirest.setHttpClient(Utils.getClient());
        HttpRequestWithBody request = null;
        try {
            switch (command){
                case ADD_HOOK: {
                    hookObj = "{\"exe\":\"create-jenkins-jobs.sh\",\"safe_path\":true,\"params\":\""+credentials.getUsername()+"\\\\r\\\\n"+credentials.getPassword().getPlainText()+"\"}";
                    setupHook(Unirest.put(hookUrl),hookObj,listener);
                    break;
                }
                default:
                case REMOVE_HOOK:{
                     hookObj = "{\"exe\":\"create-jenkins-jobs.sh\",\"safe_path\":true,\"params\":\"\"}";
                     setupHook(Unirest.put(hookUrl),hookObj,listener);
                     setupHook(Unirest.delete(hookUrl),hookObj,listener);
                    break;
                }

            }
            return true;
        } catch (UnirestException e) {
            e.printStackTrace();
            listener.getLogger().println(ExceptionUtils.getStackTrace(e));
            listener.getLogger().println(e.getMessage());
        }
        return false;
    }


    public boolean pushToGit(TaskListener listener,FilePath workspace){
        try {
            Git git = Git.with(listener, new EnvVars(EnvVars.masterEnvVars)).in(new File(this.localFolder));
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
            listener.getLogger().println(ExceptionUtils.getStackTrace(e));
            listener.getLogger().println(e.getMessage());
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

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Project project, @QueryParameter String credentialsId, @QueryParameter String serverUrl) {
            return new StandardListBoxModel()
                    .includeMatchingAs(
                            Tasks.getAuthenticationOf((Queue.Task) project),
                            project,
                            StandardUsernameCredentials.class,
                            GitURIRequirementsBuilder.fromUri(serverUrl).build(),
                            GitClient.CREDENTIALS_MATCHER)
                    .includeCurrentValue(credentialsId);


        }
        public FormValidation doTestConnection(@QueryParameter("serverUrl") final String serverUrl,
                                               @QueryParameter("credentialsId") final String credentialsId,@AncestorInPath Project project) throws IOException, ServletException {
            String apiEndPoint= serverUrl+"/rest/api/1.0/projects";
            try {
                StandardUsernamePasswordCredentials credentials=null;
                List<StandardUsernamePasswordCredentials> credentialsList = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,project);
                for (StandardUsernamePasswordCredentials c:credentialsList){
                    if (c.getId().equals(credentialsId)){
                        credentials = c;
                        break;

                    }
                }
                if (credentials != null) {
                    Unirest.setHttpClient(Utils.getClient());
                    HttpResponse<JsonNode> response = Unirest.get(apiEndPoint).basicAuth(credentials.getUsername(), credentials.getPassword().getPlainText()).asJson();
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

        public FormValidation doCheckServerUrl(@QueryParameter String serverUrl, @QueryParameter String credentialsId,@AncestorInPath Project project ) throws IOException, ServletException {
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
                // There might be no "stream handler" in URL for ssh or git. We always replace the protocol by http for this check.
                String httpUrl = input.replaceFirst("^[a-zA-Z]+://", "http://");
                new URI(httpUrl).toURL();
                return true;
            } catch (MalformedURLException e) {
                return false;
            } catch (URISyntaxException e) {
                return false;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

    }

    public static class BProject {
        String projectName;
        @DataBoundConstructor
        public BProject(String projectName){
            this.projectName = projectName;
        }
    }
}

