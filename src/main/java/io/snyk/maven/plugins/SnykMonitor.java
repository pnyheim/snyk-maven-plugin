package io.snyk.maven.plugins;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Created by dror on 15/01/2017.
 *
 * Runs 'snyk monitor' on the enclosing project
 */
@Mojo( name = "monitor")
public class SnykMonitor extends AbstractMojo {

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    // The entry point to Aether, the component that's doing all the work
    @Component
    private RepositorySystem repoSystem;

    // The current repository/network configuration of Maven.
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Component
    private RuntimeInformation runtimeInformation;

    // specific snyk plugin configurations
    @Parameter
    private String apiToken = "";

    @Parameter
    private String org = "";

    @Parameter
    private String endpoint = Constants.DEFAULT_ENDPOINT;

    private String baseUrl = "";

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            executeInternal();
        } catch(MojoExecutionException e) {
            throw e;
        } catch(MojoFailureException e) {
            throw e;
        } catch(Throwable t) {
            getLog().error(Constants.ERROR_GENERAL);
        }
    }

    private void executeInternal()
            throws MojoExecutionException, MojoFailureException, IOException, ParseException {
        if(!validateParameters()) {
            return;
        }

        JSONObject projectTree = new ProjectTraversal(
                project, repoSystem, repoSession).getTree();
        HttpResponse response = sendDataToSnyk(projectTree);
        parseResponse(response);
    }

    private boolean validateParameters() throws MojoExecutionException {
        boolean validated = true;
        if(apiToken.equals("")) {
            Constants.displayAuthError(getLog());
            validated = false;
        }
        baseUrl = Constants.parseEndpoint(endpoint);

        return validated;
    }

    private HttpResponse sendDataToSnyk(JSONObject projectTree)
            throws IOException, ParseException {
        HttpPut request = new HttpPut(baseUrl + "/api/monitor/maven");
        request.addHeader("authorization", "token " + apiToken);
        request.addHeader("x-is-ci", "false"); // how do we know ??
        request.addHeader("content-type", "application/json");

        JSONObject jsonDependencies = prepareRequestBody(projectTree);
        HttpEntity entity = new StringEntity(jsonDependencies.toString());
        request.setEntity(entity);

        HttpClient client = HttpClientBuilder.create().build();
        return client.execute(request);
    }

    private JSONObject prepareRequestBody(JSONObject projectTree) {
        JSONObject body = new JSONObject();

        JSONObject meta = new JSONObject();
        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();
        String version = project.getVersion();
        meta.put("method", "maven-plugin"); // maybe put "plugin" ??
        try {
            meta.put("hostname", InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            meta.put("hostname", "");
        }
        meta.put("id", groupId + ":" + artifactId);
        // TBD: find out whether we're inside a build machine
        meta.put("ci", "false");
        meta.put("maven", runtimeInformation.getMavenVersion());
        meta.put("name", groupId + ":" + artifactId);
        meta.put("version", version);
        meta.put("org", org);
        body.put("meta", meta);
        body.put("package", projectTree);

        return body;
    }

    private String getMonitorWebURL(String org, String id) {
        String url = baseUrl + "/org/" + org + "/monitor/" + id;
        return url;
    }

    private void parseResponse(HttpResponse response)
            throws IOException, ParseException, MojoFailureException {
        if(response.getStatusLine().getStatusCode() >= 400) {
            processError(response);
            return;
        }

        JSONParser parser = new JSONParser();
        JSONObject jsonObject = (JSONObject)parser.parse(
                new BufferedReader(
                        new InputStreamReader(
                                response.getEntity().getContent())));

        Boolean ok = (Boolean) jsonObject.get("ok");
        if(ok != null && ok == true) {
            getLog().info("Captured a snapshot of this project's dependencies.");
            getLog().info("Explore this snapshot at " +
                    getMonitorWebURL((String)jsonObject.get("org"), (String)jsonObject.get("id")));
            getLog().info("");
            getLog().info("Notifications about newly disclosed vulnerabilities " +
                    "related to these dependencies will be emailed to you.");
            getLog().info("");
        } else if(jsonObject.get("error") != null) {
            getLog().error("There was a problem monitoring the project: "
                    + jsonObject.get("error"));
        } else if(jsonObject.get("message") != null) {
            getLog().warn("Could not complete the monitoring action: "
                    + jsonObject.get("message"));
        }
    }

    private void processError(HttpResponse response) {
        // process an error in the response object
        if(response.getStatusLine().toString().contains("401")) {
            Constants.displayAuthError(getLog());
        } else {
            getLog().error(Constants.ERROR_GENERAL);
        }
    }

}