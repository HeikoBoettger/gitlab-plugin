package com.dabsquared.gitlabjenkins.trigger.label;

import com.dabsquared.gitlabjenkins.Messages;
import com.dabsquared.gitlabjenkins.connection.GitLabConnectionProperty;
import com.dabsquared.gitlabjenkins.service.GitLabProjectBranchesService;
import com.dabsquared.gitlabjenkins.service.GitLabProjectLabelsService;
import com.dabsquared.gitlabjenkins.trigger.WebHookRevisionParameterAction;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.Job;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Robin Müller
 */
public final class ProjectLabelsProvider {

    private static final Logger LOGGER = Logger.getLogger(ProjectLabelsProvider.class.getName());
    private static final ProjectLabelsProvider INSTANCE = new ProjectLabelsProvider();

    private ProjectLabelsProvider() {
    }

    public static ProjectLabelsProvider instance() {
        return INSTANCE;
    }

    private List<String> getProjectLabels(Job<?, ?> project) {
        final URIish sourceRepository = getSourceRepoURLDefault(project);
        GitLabConnectionProperty connectionProperty = project.getProperty(GitLabConnectionProperty.class);
        if (connectionProperty != null && connectionProperty.getClient() != null) {
            return GitLabProjectLabelsService.instance().getLabels(connectionProperty.getClient(), sourceRepository.toString());
        } else {
            LOGGER.log(Level.WARNING, "getProjectLabels: gitlabHostUrl hasn't been configured globally. Job {0}.", project.getFullName());
            return Collections.emptyList();
        }
    }

    public AutoCompletionCandidates doAutoCompleteLabels(Job<?, ?> job, String query) {
        AutoCompletionCandidates result = new AutoCompletionCandidates();
        // show all suggestions for short strings
        if (query.length() < 2) {
            result.add(getProjectLabelsAsArray(job));
        } else {
            for (String branch : getProjectLabelsAsArray(job)) {
                if (branch.toLowerCase().contains(query.toLowerCase())) {
                    result.add(branch);
                }
            }
        }
        return result;
    }

    public FormValidation doCheckLabels(@AncestorInPath final Job<?, ?> project, @QueryParameter final String value) {
        if (!project.hasPermission(Item.CONFIGURE) || containsNoLabel(value)) {
            return FormValidation.ok();
        }

        try {
            return checkMatchingLabels(value, getProjectLabels(project));
        } catch (GitLabProjectBranchesService.BranchLoadingException e) {
            return FormValidation.warning(project.hasPermission(Jenkins.ADMINISTER) ? e : null, Messages.GitLabPushTrigger_CannotCheckBranches());
        }
    }

    private FormValidation checkMatchingLabels(@QueryParameter String value, List<String> labels) {
        Set<String> matchingLabels = new HashSet<>();
        Set<String> unknownLabels = new HashSet<>();
        for (String label : Splitter.on(',').omitEmptyStrings().trimResults().split(value)) {
            if (labels.contains(label)) {
                matchingLabels.add(label);
            } else {
                unknownLabels.add(label);
            }
        }
        if (unknownLabels.isEmpty()) {
            return FormValidation.ok(Messages.GitLabPushTrigger_LabelsMatched(matchingLabels.size()));
        } else {
            return FormValidation.warning(Messages.GitLabPushTrigger_LabelsNotFound(Joiner.on(", ").join(unknownLabels)));
        }
    }

    private boolean containsNoLabel(@QueryParameter String value) {
        return StringUtils.isEmpty(value) || StringUtils.containsOnly(value, new char[]{',', ' '});
    }

    private String[] getProjectLabelsAsArray(Job<?, ?> job) {
        try {
            List<String> labels = getProjectLabels(job);
            return labels.toArray(new String[labels.size()]);
        } catch (GitLabProjectLabelsService.LabelLoadingException e) {
            LOGGER.log(Level.FINEST, "Failed to load labels from GitLab. Please check the logs and your configuration.", e);
        }
        return new String[0];
    }


    /**
     * Get the URL of the first declared repository in the project configuration.
     * Use this as default source repository url.
     *
     * @return URIish the default value of the source repository url
     * @throws IllegalStateException Project does not use git scm.
     */
    private URIish getSourceRepoURLDefault(Job<?, ?> job) {
        List<WebHookRevisionParameterAction> actions = job.getActions(WebHookRevisionParameterAction.class);
        return getFirstRepoURL(actions);
    }

    private URIish getFirstRepoURL(List<WebHookRevisionParameterAction> actions) {
        if (!actions.isEmpty()) {
            WebHookRevisionParameterAction action = actions.get(actions.size()-1);
            return action.getRepoURI();
        }
        throw new IllegalStateException(Messages.GitLabPushTrigger_NoSourceRepository());
    }
    
}
