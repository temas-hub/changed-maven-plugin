package org.apache.maven.plugin.dependency.changed;


import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.dependency.utils.DependencyUtil;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.scm.ChangeSet;
import org.apache.maven.scm.ScmBranch;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.changelog.ChangeLogScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.SerializingDependencyNodeVisitor;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Gets dependencies list along with latest commit info
 *
 * @author Artem Zhdanov <azhdanov@griddynamics.com>
 * @since 5/27/13
 * @goal get
 */

public class ChangedDependenciesMojo extends AbstractMojo {

    public static final String FILE_EXT = ".tree";
    /**
     * @parameter expression="${basedir}"
     */
    private File basedir;
    /**
     * Defines list of artifacts groups which are checked for changes on VCS
     * @parameter required = false
     */
    private List<String> includedGroupList;

    /**
     *  Defines the number days before the current time. This parameter limits the period which VCS commit is checked against.
     *  @parameter expression="${numberDaysToCheck}" required = true default-value = 30
     */
    private int numberDaysToCheck;
    /**
     * The dependency tree builder to use.
     * @component role=org.apache.maven.shared.dependency.graph.DependencyGraphBuilder roleHint = "default"
     */
    private DependencyGraphBuilder dependencyGraphBuilder;

    /**
     * Tool that gets a configured SCM repository from release configuration.
     *
     * @component
     */
    private ScmRepositoryConfigurator scmRepositoryConfigurator;

    /**
     * The SCM manager.
     *
     * @component
     */
    private ScmManager scmManager;

    /**
     * @component
     */
    private MavenProject project;

    /**
     * @component
     */
    private Settings settings;

    /**
     * @component  role=org.apache.maven.project.MavenProjectBuilder
     */
    private MavenProjectBuilder builder;
    /**
     * Location of the local repository.
     * @parameter  expression="${localRepository}" readonly = true required = true
     */
    private ArtifactRepository localRepo;
    /**
     * List of Remote Repositories used by the resolver
     * @parameter  expression="${project.remoteArtifactRepositories}" readonly=true required=true
     */
    protected List<ArtifactRepository> remoteRepos;


    public class ChangedArtifactsTreeBuilder implements DependencyNodeVisitor {

        /**
         * The writer to serialize to.
         */
        private final PrintWriter writer;

        /**
         * The tokens to use when serializing the dependency tree.
         */
        private final SerializingDependencyNodeVisitor.TreeTokens tokens;

        /**
         * The depth of the currently visited dependency node.
         */
        private int depth;

        // constructors -----------------------------------------------------------

        /**
         * Creates a dependency node visitor that serializes visited nodes to the specified writer using whitespace tokens.
         *
         * @param writer
         *            the writer to serialize to
         */
        public ChangedArtifactsTreeBuilder(Writer writer)
        {
            this( writer, SerializingDependencyNodeVisitor.STANDARD_TOKENS );
        }

        /**
         * Creates a dependency node visitor that serializes visited nodes to the specified writer using the specified
         * tokens.
         *
         * @param writer
         *            the writer to serialize to
         * @param tokens
         *            the tokens to use when serializing the dependency tree
         */
        public ChangedArtifactsTreeBuilder(Writer writer, SerializingDependencyNodeVisitor.TreeTokens tokens)
        {
            if ( writer instanceof PrintWriter )
            {
                this.writer = (PrintWriter) writer;
            }
            else
            {
                this.writer = new PrintWriter( writer, true );
            }

            this.tokens = tokens;

            depth = 0;
        }

        // DependencyNodeVisitor methods ------------------------------------------

        /**
         * {@inheritDoc}
         */
        public boolean visit( DependencyNode node )
        {
            depth++;
            if (includedGroupList != null && !includedGroupList.contains(node.getArtifact().getGroupId())) return false;
            indent( node );

            writer.write("[");
            writer.write(node.getArtifact().getArtifactId());
            writer.write("(");
            writer.write(node.getArtifact().getVersion());
            writer.write(")");
            writer.write("]");

            MavenProject project = null;
            ScmRepository scmRepo = null;
            ScmProvider scmProvider = null;
            try
            {
                project = builder.buildFromRepository( node.getArtifact(), remoteRepos, localRepo );
                if (project.getScm() == null || project.getScm().getConnection() == null) {
                    writer.println();
                    return true;
                }

                scmRepo = scmManager.makeScmRepository( project.getScm().getConnection().replaceAll(" ", "") );

                ScmProviderRepository repository = scmRepo.getProviderRepository();
                repository.setPersistCheckout(false);
                scmProvider = scmManager.getProviderByRepository( scmRepo );
            }
            catch ( ScmRepositoryException e ) {
                writer.write(" ERROR->Cannot get changes by (" + project.getScm().getConnection() + ")");
            }
            catch ( NoSuchScmProviderException e ) {
                writer.write(" ERROR->Cannot get changes by (" + project.getScm().getConnection() + ")");
            }
            catch (ProjectBuildingException ex) {
                writer.write(" ERROR->Cannot create maven project from" + node.getArtifact().toString());
            }

            if (project == null || scmRepo == null || scmProvider == null) {
                writer.println();
                return true;
            }

            try {
                ScmFileSet baseDir = new ScmFileSet(basedir );
                ChangeLogScmResult changeLogScmResult =
                        scmProvider.changeLog(scmRepo, baseDir, null, null, numberDaysToCheck, (ScmBranch) null);
                if (!changeLogScmResult.isSuccess()) {
                    writer.write(" ERROR->Cannot get changes by " + project.getScm().getConnection());
                    writer.println();
                }
                else {
                    List<ChangeSet> changeSets = changeLogScmResult.getChangeLog().getChangeSets();

                    if (changeSets.size() > 0)
                    {
                        // get the latest change
                        ChangeSet changeSet = changeSets.listIterator(changeSets.size()).previous();
                        writer.write("<=Author=");
                        writer.write(changeSet.getAuthor());
                        writer.write(" Date=");
                        writer.write(changeSet.getDateFormatted());
                        writer.write("_");
                        writer.write(changeSet.getTimeFormatted());
                        writer.write(" Revision=");
                        writer.write(changeSet.getRevision());
                        writer.write(" Comment=");
                        writer.write(changeSet.getComment());
                    }
                    else {
                        writer.println();
                    }
                }
            } catch (ScmException e) {
                throw new RuntimeException(e);
            }


            return true;
        }

        /**
         * {@inheritDoc}
         */
        public boolean endVisit( DependencyNode node )
        {
            depth--;

            return true;
        }

        // private methods --------------------------------------------------------

        /**
         * Writes the necessary tokens to indent the specified dependency node to this visitor's writer.
         *
         * @param node
         *            the dependency node to indent
         */
        private void indent( DependencyNode node )
        {
            for ( int i = 1; i < depth; i++ )
            {
                writer.write( tokens.getFillIndent( isLast( node, i ) ) );
            }

            if ( depth > 0 )
            {
                writer.write( tokens.getNodeIndent( isLast( node ) ) );
            }
        }

        /**
         * Gets whether the specified dependency node is the last of its siblings.
         *
         * @param node
         *            the dependency node to check
         * @return <code>true</code> if the specified dependency node is the last of its last siblings
         */
        private boolean isLast( DependencyNode node )
        {
            // TODO: remove node argument and calculate from visitor calls only

            DependencyNode parent = node.getParent();

            boolean last;

            if ( parent == null )
            {
                last = true;
            }
            else
            {
                List<DependencyNode> siblings = parent.getChildren();

                last = ( siblings.indexOf( node ) == siblings.size() - 1 );
            }

            return last;
        }

        /**
         * Gets whether the specified dependency node ancestor is the last of its siblings.
         *
         * @param node
         *            the dependency node whose ancestor to check
         * @param ancestorDepth
         *            the depth of the ancestor of the specified dependency node to check
         * @return <code>true</code> if the specified dependency node ancestor is the last of its siblings
         */
        private boolean isLast( DependencyNode node, int ancestorDepth )
        {
            // TODO: remove node argument and calculate from visitor calls only

            int distance = depth - ancestorDepth;

            while ( distance-- > 0 )
            {
                node = node.getParent();
            }

            return isLast( node );
        }

    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        // create if necessary output file
        File outFile = null;
        String outFileName = null;
        try {
            outFileName = project.getName().replaceAll("\\W", "_") + FILE_EXT;
            outFile = new File(outFileName);

            if (outFile.exists()) {
                outFile.delete();
            }
            outFile.createNewFile();

        } catch (IOException e) {
            getLog().error("Error creating output file with name" + outFileName,e);
            throw new MojoExecutionException( e.getMessage());
        }

        // build dependency and traverse it to check changed
        try {
            init();
            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph( project, null );

            String dependencyTreeString = serializeDependencyTree( rootNode );

            DependencyUtil.write( dependencyTreeString, outFile.getAbsoluteFile(), true, getLog() );

        } catch (DependencyGraphBuilderException e) {
            getLog().error(e);
            throw new MojoExecutionException( e.getMessage());
        } catch (IOException e) {
            getLog().error(e);
            throw new MojoExecutionException( e.getMessage());
        }

    }

    private void init() {
        if (includedGroupList == null) {
            includedGroupList = new ArrayList<String>(1);
        }
        if (includedGroupList.isEmpty()) {
            includedGroupList.add(project.getGroupId());
        }
    }

    private String serializeDependencyTree( DependencyNode rootNode)
    {
        StringWriter writer = new StringWriter();
        DependencyNodeVisitor visitor = new BuildingDependencyNodeVisitor(new ChangedArtifactsTreeBuilder(writer));

        rootNode.accept( visitor );
        return writer.toString();
    }
}
