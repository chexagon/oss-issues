package com.crimsonhexagon.issues;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.IssueManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GitHub;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) throws IOException, XmlPullParserException {
        if (args.length != 3) {
            System.err.println("usage: Main [PARENT POM] [MAVEN PROJECT] [LOCAL MAVEN REPO]");
            System.exit(1);
        }

        final Map<String, String> parentPomProperties = new HashMap<>();
        {
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            FileReader reader = new FileReader(args[0]);
            Model model = pomReader.read(reader);
            model.setPomFile(new File(args[0]));
            MavenProject project = new MavenProject(model);
            project.getProperties().forEach((k,v) -> parentPomProperties.put(k.toString(), v.toString()));
        }

        final List<File> pomFiles = Files
            .walk(Paths.get(args[1]), 2)
            .filter(p -> p.getFileName().endsWith("pom.xml"))
            .map(Path::toFile)
            .collect(toList());

        final Set<MavenCoordinate> unique = new HashSet<>();

        for(final File pomFile : pomFiles) {
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            FileReader reader         = new FileReader(pomFile);
            Model model               = pomReader.read(reader);
            model.setPomFile(pomFile);
            MavenProject project = new MavenProject(model);

            final List<Dependency> dependencies = project.getDependencies();
            for (final Dependency dep : dependencies) {
                String version = dep.getVersion();
                Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(version);
                final StringBuilder bldr = new StringBuilder();
                while (m.find()) {
                    ofNullable(parentPomProperties.get(m.group(1)))
                        .ifPresent(val -> m.appendReplacement(bldr, val));
                }
                m.appendTail(bldr);
                unique.add(new MavenCoordinate(dep.getGroupId(), dep.getArtifactId(), bldr.toString()));
            }
        }

        final List<Pair<MavenCoordinate, List<String>>> coordUrls = unique.stream()
            .filter(MavenCoordinate::validCoord)
            .map(coord -> Pair.of(coord, resolveIssueURL(coord, Paths.get(args[2]))))
            .collect(toList());

        coordUrls.stream()
            .filter(p -> {
                final List<String> urls = p.getRight();
                return !urls.isEmpty() && urls.stream()
                    .anyMatch(u ->
                        u.contains("issue")
                            || u.contains("atlassian")
                            || u.contains("jira"));
            })
            .forEach(p -> {
                System.out.println("Project: " + p.getLeft().artifactId);
                p.getRight().forEach(u -> {
                    System.out.println("  - " + u);
                    if (u.contains("github") && u.contains("issues")) {
                        try {
                            final List<GHIssue> openIssues = retrieveOpenGithubIssues(u);
                            if (openIssues != null) {
                                for (final GHIssue i : openIssues) {
                                    System.out.println(format("    → #%s: %s", i.getNumber(), i.getTitle()));
                                    System.out.println("      " + i.getLabels().stream().map(GHLabel::getName).collect(joining(", ")));
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            });
    }

    static List<GHIssue> retrieveOpenGithubIssues(final String githubUrl) throws IOException {
        final Pattern ghPattern = Pattern.compile("github\\.com/(\\w+)/(\\w+)/");
        final Matcher matcher = ghPattern.matcher(githubUrl);

        if (!matcher.find()) {
            return null;
        }

        final String repoSlug = matcher.group(1) + "/" + matcher.group(2);

        final GitHub gh = GitHub.connect();
        return gh.getRepository(repoSlug).getIssues(GHIssueState.OPEN);
    }

    static List<String> resolveIssueURL(final MavenCoordinate coord, final Path localMavenRepo) {

        Stream<Path> files;
        try {
            files = Files.walk(localMavenRepo
                .resolve(coord.groupId.replace(".", "/"))
                .resolve(coord.artifactId)
                .resolve(coord.version));
        } catch (IOException e) {
            return Collections.emptyList();
        }

        return files
            .filter(p -> p.toString().endsWith(".pom"))
            .map(Path::toFile)
            .findFirst()
                .map(pomFile -> {
                    try {
                        MavenXpp3Reader pomReader = new MavenXpp3Reader();
                        FileReader reader         = new FileReader(pomFile);
                        Model model               = pomReader.read(reader);
                        model.setPomFile(pomFile);
                        MavenProject project = new MavenProject(model);
                        final List<String> urls = new ArrayList<>();

                        ofNullable(project.getUrl()).ifPresent(urls::add);
                        ofNullable(project.getIssueManagement()).map(IssueManagement::getUrl).ifPresent(urls::add);

                        return urls;
                    } catch (IOException | XmlPullParserException e) {
                        throw new RuntimeException(e);
                    }
                })
            .orElse(Collections.emptyList());
    }

    static class MavenCoordinate {

        private final String groupId;
        private final String artifactId;
        private final String version;

        MavenCoordinate(final Dependency dep) {
            this.groupId    = dep.getGroupId();
            this.artifactId = dep.getArtifactId();
            this.version    = dep.getVersion();
        }

        MavenCoordinate(final String groupId, final String artifactId, final String version) {
            this.groupId    = groupId;
            this.artifactId = artifactId;
            this.version    = version;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }

        public boolean validCoord() {
            return groupId != null && artifactId != null && version != null && !version.contains("${");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MavenCoordinate)) {
                return false;
            }
            MavenCoordinate that = (MavenCoordinate) o;
            return Objects.equals(groupId, that.groupId) &&
                Objects.equals(artifactId, that.artifactId) &&
                Objects.equals(version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, artifactId, version);
        }

        @Override
        public String toString() {
            return "MavenCoordinate{" +
                "groupId='" + groupId + '\'' +
                ", artifactId='" + artifactId + '\'' +
                ", version='" + version + '\'' +
                '}';
        }
    }
}
