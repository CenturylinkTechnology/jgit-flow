package com.atlassian.maven.plugins.jgitflow.manager;

import java.io.IOException;
import java.util.List;

import com.atlassian.jgitflow.core.JGitFlow;
import com.atlassian.jgitflow.core.JGitFlowReporter;
import com.atlassian.jgitflow.core.exception.BranchOutOfDateException;
import com.atlassian.jgitflow.core.exception.JGitFlowException;
import com.atlassian.jgitflow.core.util.GitHelper;
import com.atlassian.maven.plugins.jgitflow.ReleaseContext;
import com.atlassian.maven.plugins.jgitflow.exception.JGitFlowReleaseException;
import com.atlassian.maven.plugins.jgitflow.exception.ReactorReloadException;
import com.atlassian.maven.plugins.jgitflow.helper.JGitFlowSetupHelper;
import com.atlassian.maven.plugins.jgitflow.helper.MavenExecutionHelper;
import com.atlassian.maven.plugins.jgitflow.helper.PomUpdater;
import com.atlassian.maven.plugins.jgitflow.helper.ProjectHelper;
import com.atlassian.maven.plugins.jgitflow.provider.BranchLabelProvider;
import com.atlassian.maven.plugins.jgitflow.provider.ProjectCacheKey;
import com.atlassian.maven.plugins.jgitflow.util.NamingUtil;

import com.google.common.base.Splitter;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.release.exec.MavenExecutorException;
import org.apache.maven.shared.release.util.ReleaseUtil;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RefSpec;

/**
 * @since version
 */
@Component(role = FlowReleaseManager.class, hint = "feature")
public class DefaultFlowFeatureManager extends AbstractFlowReleaseManager
{
    @Requirement
    private JGitFlowSetupHelper setupHelper;

    @Requirement
    private MavenExecutionHelper mavenExecutionHelper;

    @Requirement
    private ProjectHelper projectHelper;

    @Requirement
    private BranchLabelProvider labelProvider;

    @Requirement
    private PomUpdater pomUpdater;

    @Override
    public void start(ReleaseContext ctx, List<MavenProject> reactorProjects, MavenSession session) throws JGitFlowReleaseException
    {
        JGitFlow flow = null;
        try
        {
            flow = JGitFlow.forceInit(ctx.getBaseDir(), ctx.getFlowInitContext(), ctx.getDefaultOriginUrl());

            setupHelper.runCommonSetup(flow, ctx);

            String featureName = startFeature(flow, ctx);

            if (ctx.isEnableFeatureVersions())
            {
                updateFeaturePomsWithFeatureVersion(featureName, flow, ctx, reactorProjects, session);
            }

            if (ctx.isPushFeatures())
            {
                final String prefixedBranchName = flow.getFeatureBranchPrefix() + featureName;
                RefSpec branchSpec = new RefSpec(prefixedBranchName);
                flow.git().push().setRemote("origin").setRefSpecs(branchSpec).call();
            }
        }
        catch (GitAPIException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
        catch (JGitFlowException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
        finally
        {
            if (null != flow)
            {
                flow.getReporter().flush();
            }
        }

    }

    @Override
    public void finish(ReleaseContext ctx, List<MavenProject> reactorProjects, MavenSession session) throws JGitFlowReleaseException
    {
        JGitFlow flow = null;

        MavenProject rootProject = ReleaseUtil.getRootProject(reactorProjects);
        MavenSession currentSession = session;

        try
        {
            flow = JGitFlow.forceInit(ctx.getBaseDir(), ctx.getFlowInitContext(), ctx.getDefaultOriginUrl());

            JGitFlowReporter reporter = flow.getReporter();

            setupHelper.runCommonSetup(flow, ctx);

            if (ctx.isPushFeatures() || ctx.isPullDevelop())
            {
                setupHelper.ensureOrigin(ctx.getDefaultOriginUrl(), ctx.isAlwaysUpdateOrigin(), flow);
            }

            //do a pull if needed
            if (GitHelper.remoteBranchExists(flow.git(), flow.getDevelopBranchName(), flow.getReporter()))
            {
                if (ctx.isPullDevelop())
                {
                    reporter.debugText("finishFeature", "pulling develop before remote behind check");
                    reporter.flush();

                    flow.git().checkout().setName(flow.getDevelopBranchName()).call();
                    flow.git().pull().call();
                }

                if (GitHelper.localBranchBehindRemote(flow.git(), flow.getDevelopBranchName(), flow.getReporter()))
                {
                    reporter.errorText("feature-finish", "local branch '" + flow.getDevelopBranchName() + "' is behind the remote branch");
                    reporter.flush();
                    throw new BranchOutOfDateException("local branch '" + flow.getDevelopBranchName() + "' is behind the remote branch");
                }
            }

            String featureLabel = labelProvider.getFeatureFinishName(ctx, flow);

            String prefixedBranchName = flow.getFeatureBranchPrefix() + featureLabel;

            // make sure we are on specific feature branch
            flow.git().checkout().setName(prefixedBranchName).call();

            //make sure we're not behind remote
            if (GitHelper.remoteBranchExists(flow.git(), prefixedBranchName, reporter))
            {
                if (GitHelper.localBranchBehindRemote(flow.git(), prefixedBranchName, reporter))
                {
                    reporter.errorText("feature-finish", "local branch '" + prefixedBranchName + "' is behind the remote branch");
                    reporter.flush();
                    throw new BranchOutOfDateException("local branch '" + prefixedBranchName + "' is behind the remote branch");
                }
            }

            if (ctx.isEnableFeatureVersions())
            {
                updateFeaturePomsWithNonFeatureVersion(featureLabel, flow, ctx, reactorProjects, session);

                //reload the reactor projects
                MavenSession featureSession = mavenExecutionHelper.getSessionForBranch(prefixedBranchName, ReleaseUtil.getRootProject(reactorProjects), session, ctx);
                List<MavenProject> featureProjects = featureSession.getSortedProjects();

                currentSession = featureSession;
                rootProject = ReleaseUtil.getRootProject(featureProjects);
            }


            if (!ctx.isNoBuild())
            {
                try
                {
                    mavenExecutionHelper.execute(rootProject, ctx, currentSession);
                }
                catch (MavenExecutorException e)
                {
                    throw new JGitFlowReleaseException("Error building: " + e.getMessage(), e);
                }
            }

            getLogger().info("running jgitflow feature finish...");
            flow.featureFinish(featureLabel)
                .setKeepBranch(ctx.isKeepBranch())
                .setSquash(ctx.isSquash())
                .setRebase(ctx.isFeatureRebase())
                .setAllowUntracked(ctx.isAllowUntracked())
                .setPush(ctx.isPushFeatures())
                .setNoMerge(ctx.isNoFeatureMerge())
                .setScmMessagePrefix(ctx.getScmCommentPrefix())
                .setScmMessageSuffix(ctx.getScmCommentSuffix())
                .call();

            //make sure we're on develop
            flow.git().checkout().setName(flow.getDevelopBranchName()).call();

        }
        catch (JGitFlowException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        catch (GitAPIException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        catch (ReactorReloadException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        catch (IOException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        finally
        {
            if (null != flow)
            {
                flow.getReporter().flush();
            }
        }
    }

    @Override
    public void deploy(ReleaseContext ctx, List<MavenProject> reactorProjects, MavenSession session, String buildNumber, String goals) throws JGitFlowReleaseException
    {
        JGitFlow flow = null;

        MavenProject rootProject = ReleaseUtil.getRootProject(reactorProjects);
        MavenSession currentSession = session;

        try
        {
            flow = JGitFlow.forceInit(ctx.getBaseDir(), ctx.getFlowInitContext(), ctx.getDefaultOriginUrl());
            setupHelper.runCommonSetup(flow, ctx);

            String featureLabel = labelProvider.getFeatureFinishName(ctx, flow);

            // make sure we are on specific feature branch
            flow.git().checkout().setName(flow.getFeatureBranchPrefix() + featureLabel).call();

            //update poms with feature name version
            MavenSession featureSession = mavenExecutionHelper.getSessionForBranch(flow.getFeatureBranchPrefix() + featureLabel, ReleaseUtil.getRootProject(reactorProjects), session, ctx);
            List<MavenProject> featureProjects = featureSession.getSortedProjects();

            String featureVersion = NamingUtil.camelCaseOrSpaceToDashed(featureLabel);
            featureVersion = StringUtils.replace(featureVersion, "-", "_");

            if (StringUtils.isNotBlank(buildNumber))
            {
                featureVersion = featureVersion + "-build" + buildNumber;
            }
            else
            {
                featureVersion = featureVersion + "-SNAPSHOT";
            }

            pomUpdater.removeSnapshotFromFeatureVersions(ProjectCacheKey.FEATURE_DEPLOY_LABEL, featureVersion, ctx, reactorProjects);

            rootProject = ReleaseUtil.getRootProject(featureProjects);
            featureSession = mavenExecutionHelper.reloadReactor(rootProject, session);

            rootProject = ReleaseUtil.getRootProject(featureSession.getSortedProjects());


            if (!ctx.isNoBuild())
            {
                String mvnGoals = "clean install deploy";
                if (StringUtils.isNotBlank(goals))
                {
                    mvnGoals = goals;
                }

                try
                {
                    for (String goal : Splitter.on(" ").trimResults().omitEmptyStrings().split(mvnGoals))
                    {
                        mavenExecutionHelper.execute(rootProject, ctx, featureSession, goal);
                    }
                }
                catch (MavenExecutorException e)
                {
                    throw new JGitFlowReleaseException("Error building: " + e.getMessage(), e);
                }
            }

            //revert our local changes
            flow.git().reset().setMode(ResetCommand.ResetType.HARD).call();

        }
        catch (JGitFlowException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        catch (GitAPIException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        catch (ReactorReloadException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        catch (IOException e)
        {
            throw new JGitFlowReleaseException("Error finish feature: " + e.getMessage(), e);
        }
        finally
        {
            if (null != flow)
            {
                flow.getReporter().flush();
            }
        }
    }

    private String startFeature(JGitFlow flow, ReleaseContext ctx) throws JGitFlowReleaseException
    {
        String featureName = "";

        try
        {
            //make sure we're on develop
            flow.git().checkout().setName(flow.getDevelopBranchName()).call();

            featureName = labelProvider.getFeatureStartName(ctx, flow);

            if (ctx.isPushFeatures())
            {
                setupHelper.ensureOrigin(ctx.getDefaultOriginUrl(), ctx.isAlwaysUpdateOrigin(), flow);
            }

            flow.featureStart(featureName)
                .setAllowUntracked(ctx.isAllowUntracked())
                .setPush(ctx.isPushFeatures())
                .setStartCommit(ctx.getStartCommit())
                .setScmMessagePrefix(ctx.getScmCommentPrefix())
                .setScmMessageSuffix(ctx.getScmCommentSuffix())
                .call();
        }
        catch (GitAPIException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
        catch (JGitFlowException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }

        return featureName;
    }

    private void updateFeaturePomsWithFeatureVersion(String featureName, JGitFlow flow, ReleaseContext ctx, List<MavenProject> originalProjects, MavenSession session) throws JGitFlowReleaseException
    {
        try
        {
            //reload the reactor projects
            MavenSession featureSession = mavenExecutionHelper.getSessionForBranch(flow.getFeatureBranchPrefix() + featureName, ReleaseUtil.getRootProject(originalProjects), session, ctx);
            List<MavenProject> featureProjects = featureSession.getSortedProjects();

            String featureVersion = NamingUtil.camelCaseOrSpaceToDashed(featureName);
            featureVersion = StringUtils.replace(featureVersion, "-", "_");

            pomUpdater.addFeatureVersionToSnapshotVersions(ProjectCacheKey.FEATURE_START_LABEL, featureVersion, ctx, featureProjects);

            projectHelper.commitAllPoms(flow.git(), featureProjects, ctx.getScmCommentPrefix() + "updating poms for " + featureVersion + " version" + ctx.getScmCommentSuffix());
        }
        catch (GitAPIException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
        catch (ReactorReloadException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
        catch (IOException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
        catch (JGitFlowException e)
        {
            throw new JGitFlowReleaseException("Error starting feature: " + e.getMessage(), e);
        }
    }

    private void updateFeaturePomsWithNonFeatureVersion(String featureLabel, JGitFlow flow, ReleaseContext ctx, List<MavenProject> originalProjects, MavenSession session) throws JGitFlowReleaseException
    {
        try
        {
            //reload the reactor projects
            MavenSession featureSession = mavenExecutionHelper.getSessionForBranch(flow.getFeatureBranchPrefix() + featureLabel, ReleaseUtil.getRootProject(originalProjects), session, ctx);
            List<MavenProject> featureProjects = featureSession.getSortedProjects();

            String featureVersion = NamingUtil.camelCaseOrSpaceToDashed(featureLabel);
            featureVersion = StringUtils.replace(featureVersion, "-", "_");

            pomUpdater.removeFeatureVersionFromSnapshotVersions(ProjectCacheKey.FEATURE_FINISH_LABEL, featureVersion, ctx, featureProjects);

            projectHelper.commitAllPoms(flow.git(), featureProjects, ctx.getScmCommentPrefix() + "updating poms for " + featureVersion + " version" + ctx.getScmCommentSuffix());
        }
        catch (GitAPIException e)
        {
            throw new JGitFlowReleaseException("Error finishing feature: " + e.getMessage(), e);
        }
        catch (ReactorReloadException e)
        {
            throw new JGitFlowReleaseException("Error finishing feature: " + e.getMessage(), e);
        }
        catch (IOException e)
        {
            throw new JGitFlowReleaseException("Error finishing feature: " + e.getMessage(), e);
        }
        catch (JGitFlowException e)
        {
            throw new JGitFlowReleaseException("Error finishing feature: " + e.getMessage(), e);
        }
    }

}
