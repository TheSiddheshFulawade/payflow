package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

/** Checks the README diagrams, badges and links as files: safe, accessible, animated, no external loads (PF-11). */
class ReadmeAssetsTest {

    private static final Path ROOT = repoRoot();
    private static final String DIAGRAMS = "docs/diagrams/";
    private static final String BADGES = "docs/badges/";

    private static final List<String> FORBIDDEN_IN_DIAGRAMS =
            List.of("<script", "@import", "@font-face", "<foreignObject", "javascript:");
    private static final Pattern EVENT_ATTRIBUTE = Pattern.compile("\\son[a-z]+\\s*=");
    private static final Pattern EXTERNAL_REFERENCE =
            Pattern.compile("(?:href\\s*=\\s*[\"']|url\\(\\s*[\"']?)\\s*(?:https?:|//)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");

    @ParameterizedTest
    @ValueSource(strings = {"saga-happy-path.svg", "compensation.svg", "outbox-inbox.svg"})
    @DisplayName("PF-11 diagram is a small, well-formed, accessible, animated SVG")
    void diagramIsAccessibleAndAnimated(String name) throws Exception {
        String svg = readAsset(DIAGRAMS + name);

        assertThat(svg.getBytes(StandardCharsets.UTF_8).length)
                .as("size of %s", name)
                .isLessThan(30 * 1024);
        assertThat(parseHardened(svg).getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(svg).contains("<title", "<desc");
        assertThat(svg).contains("prefers-reduced-motion", "prefers-color-scheme: dark");
        assertThat(svg).contains("@keyframes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"saga-happy-path.svg", "compensation.svg", "outbox-inbox.svg"})
    @DisplayName("PF-11 diagram has no script, font or style imports, event handlers or external references")
    void diagramLoadsNothingExternalAndRunsNoCode(String name) throws IOException {
        String svg = readAsset(DIAGRAMS + name);

        for (String forbidden : FORBIDDEN_IN_DIAGRAMS) {
            assertThat(svg).as("%s must not contain %s", name, forbidden).doesNotContainIgnoringCase(forbidden);
        }
        assertThat(svg).as("event attribute in %s", name).doesNotContainPattern(EVENT_ATTRIBUTE);
        assertThat(svg).as("external href/url() in %s", name).doesNotContainPattern(EXTERNAL_REFERENCE);
    }

    @Test
    @DisplayName("PF-11 the diagram folder holds exactly the three diagrams")
    void exactlyTheThreeDiagrams() throws IOException {
        Path dir = ROOT.resolve("docs/diagrams");
        assertThat(dir).as("docs/diagrams").isDirectory();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".svg")))
                    .containsExactlyInAnyOrder("saga-happy-path.svg", "compensation.svg", "outbox-inbox.svg");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"licence-mit.svg", "java-21.svg", "release-v1.0.0.svg"})
    @DisplayName("PF-11 badge is a tiny, well-formed SVG without script")
    void badgeIsTinyAndSafe(String name) throws Exception {
        String svg = readAsset(BADGES + name);

        assertThat(svg.getBytes(StandardCharsets.UTF_8).length)
                .as("size of %s", name)
                .isLessThan(4 * 1024);
        assertThat(parseHardened(svg).getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(svg).doesNotContainIgnoringCase("<script");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "docs/diagrams/saga-happy-path.svg",
                "docs/diagrams/compensation.svg",
                "docs/diagrams/outbox-inbox.svg",
                "docs/badges/licence-mit.svg",
                "docs/badges/java-21.svg",
                "docs/badges/release-v1.0.0.svg"
            })
    @DisplayName("PF-11 README shows each diagram and badge")
    void readmeReferencesEveryAsset(String path) throws IOException {
        assertThat(readAsset("README.md")).contains(path);
    }

    @Test
    @DisplayName("PF-11 README has the three headings in order, links DECISIONS.md and has a mermaid block")
    void readmeStructure() throws IOException {
        String readme = readAsset("README.md");

        int checked = readme.indexOf("## What is checked");
        int oneCommand = readme.indexOf("## Run it with one command");
        int decisions = readme.indexOf("## Key decisions");
        assertThat(checked).as("What is checked heading").isNotNegative();
        assertThat(oneCommand).as("Run it with one command heading").isGreaterThan(checked);
        assertThat(decisions).as("Key decisions heading").isGreaterThan(oneCommand);
        assertThat(readme).contains("docs/DECISIONS.md");
        assertThat(readme).contains("```mermaid");
    }

    @Test
    @DisplayName("PF-11 README makes no unprovable claims")
    void readmeHasNoHype() throws IOException {
        String readme = readAsset("README.md").toLowerCase(Locale.ROOT);

        assertThat(readme)
                .doesNotContain("production-ready", "production ready", "battle-tested", "bulletproof", "blazing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "docs/DECISIONS.md"})
    @DisplayName("PF-11 every relative Markdown link resolves to a file or folder")
    void relativeLinksResolve(String name) throws IOException {
        String text = readAsset(name);
        Path base = ROOT.resolve(name).getParent();

        Matcher m = MARKDOWN_LINK.matcher(text);
        int checked = 0;
        while (m.find()) {
            String target = m.group(1);
            if (target.startsWith("http") || target.startsWith("mailto") || target.startsWith("#")) {
                continue;
            }
            int hash = target.indexOf('#');
            String path = hash >= 0 ? target.substring(0, hash) : target;
            checked++;
            assertThat(base.resolve(path).normalize())
                    .as("link target '%s' in %s", target, name)
                    .exists();
        }
        assertThat(checked).as("relative links in %s", name).isPositive();
    }

    @Test
    @DisplayName("PF-11 DECISIONS.md holds the decision bullets moved out of the README")
    void decisionsFileHasTheBullets() throws IOException {
        long bullets = readAsset("docs/DECISIONS.md")
                .lines()
                .filter(l -> l.startsWith("- **"))
                .count();

        assertThat(bullets).as("lines starting with '- **'").isGreaterThanOrEqualTo(40);
    }

    // TODO-ME: read README.md, find every `](docs/DECISIONS.md#anchor)` link and assert each anchor equals a heading of
    // docs/DECISIONS.md written the GitHub way (lower case, spaces to hyphens, punctuation such as ':' and ','
    // dropped).
    // Check it: remove @Disabled and run this test (green), then break one anchor in a scratch copy of the README and
    // see it fail with that anchor named.
    @Test
    @Disabled("TODO-ME")
    @DisplayName("PF-11 every docs/DECISIONS.md anchor linked from the README names a real heading")
    void everyDecisionsAnchorInReadmeExists() {}

    private static Document parseHardened(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String readAsset(String relative) throws IOException {
        Path file = ROOT.resolve(relative);
        assertThat(file).as(relative).isRegularFile();
        return Files.readString(file);
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("SPEC.md"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root (folder with SPEC.md) not found");
        }
        return dir;
    }
}
