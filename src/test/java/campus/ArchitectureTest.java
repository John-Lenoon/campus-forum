package campus;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/** Enforces the user-requested package and persistence boundaries, not collaborator call order. */
class ArchitectureTest {
    private final Path source = Path.of(System.getProperty("architecture.source-root", "src/main"));
    private final Path javaRoot = source.resolve("java");

    private List<Path> javaFiles() throws Exception {
        try (var paths = Files.walk(javaRoot)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void productionTypesLiveInExplicitLayersAndHaveTheirOwnFiles() throws Exception {
        var layers = Set.of("controller", "service", "entity", "mapper", "dto", "config", "infrastructure", "job", "exception", "support");
        var seen = new java.util.HashSet<String>();
        for (Path path : javaFiles()) {
            String code = Files.readString(path);
            Path relative = javaRoot.relativize(path);
            String expectedPackage = relative.getParent().toString().replace('\\', '.').replace('/', '.');
            assertThat(code).as(path.toString()).contains("package " + expectedPackage + ";");
            String name = path.getFileName().toString().replace(".java", "");
            var declarations = Pattern.compile("(?m)^public (?:final )?(?:class|interface|record) (\\w+)").matcher(code);
            var types = new ArrayList<String>();
            while (declarations.find()) types.add(declarations.group(1));
            assertThat(types).as("one public top-level type: " + path).containsExactly(name);
            if (relative.getNameCount() == 2) {
                assertThat(name).isEqualTo("ForumApplication");
            } else {
                String layer = relative.getName(1).toString();
                assertThat(layers).contains(layer);
                seen.add(layer);
                if (layer.equals("controller")) assertThat(name).endsWith("Controller");
                if (layer.equals("service")) assertThat(name).endsWith("Service");
                if (layer.equals("mapper")) assertThat(name).endsWith("Mapper");
            }
        }
        assertThat(seen).containsAll(layers);
    }

    @Test
    void httpBusinessAndPersistenceDependenciesStayWithinTheirBoundaries() throws Exception {
        Pattern sql = Pattern.compile("(?i)\\b(SELECT\\s+.+?\\s+FROM|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|ALTER\\s+TABLE)\\b");
        for (Path path : javaFiles()) {
            String code = Files.readString(path);
            String layer = javaRoot.relativize(path).getParent().getFileName().toString();
            assertThat(code).as(path.toString()).doesNotContain("JdbcTemplate", "RowMapper", "java.sql.", "org.springframework.jdbc.");
            assertThat(sql.matcher(code).find()).as("SQL belongs in mapper XML: " + path).isFalse();
            if (!layer.equals("controller")) assertThat(code).doesNotContain("import campus.controller.");
            if (layer.equals("controller")) {
                assertThat(code).doesNotContain("import campus.mapper.", "import campus.entity.", "@Transactional", "@Service");
            }
            if (layer.equals("service")) {
                assertThat(code).doesNotContain("org.springframework.web.bind.annotation", "@RestController", "@Scheduled", "@RabbitListener", "java.nio.file.Files");
            }
            if (Set.of("job", "infrastructure").contains(layer)) assertThat(code).doesNotContain("import campus.mapper.");
            if (Set.of("entity", "dto").contains(layer)) assertThat(code).doesNotContain("org.springframework.", "import campus.service.", "import campus.mapper.");
        }
    }

    @Test
    void typedMybatisPlusMappersOwnAllCustomSqlAndPreserveCompositeKeys() throws Exception {
        Path mapperRoot = javaRoot.resolve("campus/mapper");
        assertThat(mapperRoot).isDirectory();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Set<String> compositeKeys = Set.of("ChunkMapper", "InteractionMapper");
        int baseMappers = 0;
        try (var paths = Files.list(mapperRoot)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> mapper = Class.forName("campus.mapper." + name);
                if (Set.of("UserMapper", "PostMapper", "CommentMapper", "ResourceMapper").contains(name)) {
                    assertThat(BaseMapper.class.isAssignableFrom(mapper)).isTrue();
                    baseMappers++;
                }
                if (compositeKeys.contains(name)) assertThat(BaseMapper.class.isAssignableFrom(mapper)).isFalse();
                Path xml = source.resolve("resources/mapper/" + name + ".xml");
                var document = factory.newDocumentBuilder().parse(xml.toFile());
                assertThat(document.getDocumentElement().getAttribute("namespace")).isEqualTo(mapper.getName());
                String mapping = Files.readString(xml);
                assertThat(mapping).doesNotContain("${");
                for (var method : mapper.getDeclaredMethods()) {
                    assertThat(Map.class.isAssignableFrom(method.getReturnType())).isFalse();
                    assertThat(method.getReturnType()).isNotEqualTo(Object.class);
                    assertThat(method.getGenericReturnType().getTypeName()).doesNotContain("java.util.Map", "java.lang.Object");
                    assertThat(mapping).as(method.toString()).contains("id=\"" + method.getName() + "\"");
                }
            }
        }
        assertThat(baseMappers).isEqualTo(4);
    }
}
