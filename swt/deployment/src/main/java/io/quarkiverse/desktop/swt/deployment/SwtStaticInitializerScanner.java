package io.quarkiverse.desktop.swt.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.jboss.jandex.ClassInfo;
import org.jboss.logging.Logger;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkus.paths.PathTree;

/**
 * Finds the classes whose static initializer uses SWT, directly or through the methods it calls.
 * <p>
 * The SWT classes are initialized at run time in native mode (their static initializers load the native libraries of
 * SWT), while Quarkus initializes the application classes and the classes of its libraries at build time. A class
 * initialized at build time creating SWT objects in its static initializer (e.g.
 * {@code static final RGB ACCENT = new RGB(0, 150, 201)}, {@code static final Point MINIMUM_SIZE}, a {@code static}
 * {@code FontData}, in the application or in a widget library) would initialize run time classes in the native image
 * builder, or put their objects in the image heap, which fails the native build. Such classes are initialized at run
 * time too, as in JVM mode.
 * <p>
 * The classes of the application and of all its libraries are scanned, whether they are in the Jandex index or not, in
 * two passes : the constant pool of every class file tells which classes reference an SWT package and which classes each
 * class references, and only the classes that reference an SWT package, directly or through other scanned classes, are
 * then scanned method by method.
 * <p>
 * Port of the {@code DesktopStaticInitializerScanner} of the Desktop AWT extension (itself a port of the
 * {@code FxStaticInitializerScanner} of the Quarkus FX extension), with the SWT packages.
 */
final class SwtStaticInitializerScanner {

    private static final Logger LOGGER = Logger.getLogger(SwtStaticInitializerScanner.class);

    private static final String CLINIT = "<clinit>()V";

    /**
     * Internal name prefixes of the SWT packages : the packages initialized at run time by the extension
     * ({@code RUNTIME_INITIALIZED_PACKAGES} of {@link SwtClassesAndResources}).
     */
    static final List<String> SWT_PACKAGES = List.of("org/eclipse/swt/");

    /**
     * {@link #SWT_PACKAGES} in the modified UTF-8 of the constant pool (ASCII), by first byte.
     */
    private static final byte[][][] SWT_PACKAGE_BYTES = swtPackageBytes();

    /**
     * The annotation of GraalVM substitutions, which are never initialized.
     */
    private static final String TARGET_CLASS = "Lcom/oracle/svm/core/annotate/TargetClass;";
    private static final byte[] TARGET_CLASS_BYTES = TARGET_CLASS.getBytes(StandardCharsets.US_ASCII);

    /**
     * The tag of a {@code CONSTANT_Class} constant pool entry.
     */
    private static final int CONSTANT_CLASS = 7;

    /**
     * Class files to scan : reads them, and gives the internal name ({@code com/example/Palette}) and the content of
     * each one that {@code wanted} accepts to the consumer. Called twice : for every class file, then for the classes
     * scanned method by method.
     */
    @FunctionalInterface
    interface ClassFiles {
        void read(Predicate<String> wanted, BiConsumer<String, byte[]> consumer);
    }

    /**
     * The first pass : whether a class references an SWT package, and the classes it references.
     */
    private record Summary(boolean swt, Set<String> references) {
    }

    private final Set<String> scannedClasses;
    // method ("owner.name descriptor") -> methods of scanned classes it invokes, or whose class it initializes
    private final Map<String, Set<String>> edges = new HashMap<>();
    private final Set<String> usingSwt = new HashSet<>();

    private SwtStaticInitializerScanner(Set<String> scannedClasses) {
        this.scannedClasses = scannedClasses;
    }

    /**
     * Scans the given classes, read with the class loader.
     *
     * @return the names of the classes to initialize at run time
     */
    static Set<String> scan(Iterable<ClassInfo> classes, ClassLoader classLoader) {
        return scan(classFiles(List.of(), classes, classLoader));
    }

    /**
     * @return the names of the classes to initialize at run time
     */
    static Set<String> scan(ClassFiles classFiles) {
        Map<String, Summary> summaries = new HashMap<>();
        classFiles.read(name -> !isSwt(name), (name, bytes) -> {
            Summary summary = summarize(name, bytes);
            if (summary != null) {
                summaries.merge(name, summary, (a, b) -> {
                    // the same class in several places (a multi-release jar...) : all of them
                    Set<String> references = new HashSet<>(a.references());
                    references.addAll(b.references());
                    return new Summary(a.swt() || b.swt(), references);
                });
            }
        });
        Set<String> candidates = candidates(summaries);
        LOGGER.debugf("Classes scanned for SWT static initializers : %d, method by method : %d", summaries.size(),
                candidates.size());
        if (candidates.isEmpty()) {
            return new TreeSet<>();
        }
        SwtStaticInitializerScanner scanner = new SwtStaticInitializerScanner(candidates);
        classFiles.read(candidates::contains, scanner::visit);
        return scanner.classesToInitializeAtRunTime();
    }

    /**
     * The class files of the given trees (the application and its libraries), then those of the classes of the index
     * that the trees do not have, read with the class loader. The versioned class files of multi-release jars are read
     * as the class they version.
     */
    static ClassFiles classFiles(Collection<PathTree> trees, Iterable<ClassInfo> indexClasses, ClassLoader classLoader) {
        return (wanted, consumer) -> {
            Set<String> found = new HashSet<>();
            for (PathTree tree : trees) {
                try {
                    tree.walk(visit -> {
                        String name = className(visit.getRelativePath("/"));
                        if (name == null) {
                            return;
                        }
                        found.add(name);
                        if (wanted.test(name)) {
                            try {
                                consumer.accept(name, Files.readAllBytes(visit.getPath()));
                            } catch (IOException e) {
                                LOGGER.debugf(e, "Unable to read %s", visit.getPath());
                            }
                        }
                    });
                } catch (RuntimeException e) {
                    LOGGER.debugf(e, "Unable to read %s", tree);
                }
            }
            for (ClassInfo classInfo : indexClasses) {
                String name = classInfo.name().toString().replace('.', '/');
                if (found.add(name) && wanted.test(name)) {
                    try (InputStream in = classLoader.getResourceAsStream(name + ".class")) {
                        if (in != null) {
                            consumer.accept(name, in.readAllBytes());
                        }
                    } catch (IOException e) {
                        LOGGER.debugf(e, "Unable to read %s", name);
                    }
                }
            }
        };
    }

    /**
     * The internal name of the class of a class file of a tree, {@code null} for the other files.
     */
    static String className(String path) {
        if (!path.endsWith(".class")) {
            return null;
        }
        String name = path.substring(0, path.length() - ".class".length());
        if (name.startsWith("META-INF/versions/")) {
            int slash = name.indexOf('/', "META-INF/versions/".length());
            name = slash < 0 ? "" : name.substring(slash + 1);
        } else if (name.startsWith("META-INF/")) {
            return null;
        }
        if (name.isEmpty() || name.equals("module-info") || name.equals("package-info")
                || name.endsWith("/package-info")) {
            return null;
        }
        return name;
    }

    /**
     * Reads the constant pool of a class file, {@code null} when it cannot be read.
     */
    private static Summary summarize(String name, byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            char[] buffer = new char[reader.getMaxStringLength()];
            Set<String> references = new HashSet<>();
            for (int i = 1; i < reader.getItemCount(); i++) {
                // the offset of the entry after its tag, 0 for the second slot of a long or double
                int offset = reader.getItem(i);
                if (offset > 0 && bytes[offset - 1] == CONSTANT_CLASS) {
                    String reference = reader.readUTF8(offset, buffer);
                    // an array class : its element type
                    int element = reference.lastIndexOf('[');
                    if (element >= 0) {
                        reference = reference.charAt(element + 1) == 'L'
                                ? reference.substring(element + 2, reference.length() - 1)
                                : null;
                    }
                    if (reference != null && !reference.equals(name)) {
                        references.add(reference);
                    }
                }
            }
            return new Summary(containsSwtPackage(bytes), references);
        } catch (RuntimeException e) {
            // e.g. class file version not supported by ASM
            LOGGER.debugf(e, "Unable to scan %s", name);
            return null;
        }
    }

    /**
     * The classes that reference an SWT package, and the classes that reference them, directly or through other
     * scanned classes : the only ones whose static initializer may use SWT.
     */
    private static Set<String> candidates(Map<String, Summary> summaries) {
        Map<String, List<String>> referrers = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        summaries.forEach((name, summary) -> {
            if (summary.swt()) {
                queue.add(name);
            }
            for (String reference : summary.references()) {
                if (summaries.containsKey(reference)) {
                    referrers.computeIfAbsent(reference, r -> new ArrayList<>()).add(name);
                }
            }
        });
        Set<String> candidates = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            for (String referrer : referrers.getOrDefault(queue.poll(), List.of())) {
                if (candidates.add(referrer)) {
                    queue.add(referrer);
                }
            }
        }
        return candidates;
    }

    private void visit(String owner, byte[] bytes) {
        try {
            if (isSubstitution(bytes)) {
                return;
            }
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] interfaces) {
                    // Initializing a class initializes its super class first
                    if (superName != null) {
                        classInitialization(owner + "." + CLINIT, superName);
                    }
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    return new InstructionVisitor(owner + "." + name + descriptor);
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            // e.g. class file version not supported by ASM
            LOGGER.debugf(e, "Unable to scan %s", owner);
        }
    }

    /**
     * Whether the class is a GraalVM substitution ({@code @TargetClass}).
     */
    private static boolean isSubstitution(byte[] bytes) {
        if (indexOf(bytes, TARGET_CLASS_BYTES, 0) < 0) {
            return false;
        }
        boolean[] annotated = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                annotated[0] |= TARGET_CLASS.equals(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return annotated[0];
    }

    private final class InstructionVisitor extends MethodVisitor {

        private final String method;

        InstructionVisitor(String method) {
            super(Opcodes.ASM9);
            this.method = method;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            reference(owner);
            reference(descriptor);
            if (scannedClasses.contains(owner)) {
                edge(owner + "." + name + descriptor);
            }
            if (opcode == Opcodes.INVOKESTATIC) {
                classInitialization(method, owner);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            reference(owner);
            reference(descriptor);
            if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
                classInitialization(method, owner);
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            reference(type);
            if (opcode == Opcodes.NEW) {
                classInitialization(method, type);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            constant(value);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            reference(descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            reference(descriptor);
            for (Object argument : bootstrapMethodArguments) {
                constant(argument);
            }
        }

        private void constant(Object value) {
            if (value instanceof Type type) {
                reference(type.getDescriptor());
            } else if (value instanceof Handle handle) {
                reference(handle.getOwner());
                reference(handle.getDesc());
            } else if (value instanceof ConstantDynamic constantDynamic) {
                reference(constantDynamic.getDescriptor());
            }
        }

        private void reference(String nameOrDescriptor) {
            if (isSwt(nameOrDescriptor)) {
                usingSwt.add(method);
            }
        }

        private void edge(String callee) {
            edges.computeIfAbsent(method, k -> new HashSet<>()).add(callee);
        }
    }

    private void classInitialization(String method, String initializedClass) {
        if (!method.startsWith(initializedClass + ".") && scannedClasses.contains(initializedClass)) {
            edges.computeIfAbsent(method, k -> new HashSet<>()).add(initializedClass + "." + CLINIT);
        }
    }

    private Set<String> classesToInitializeAtRunTime() {
        // Propagate "uses SWT" from callees to callers
        Map<String, Set<String>> callers = new HashMap<>();
        edges.forEach((caller, callees) -> callees
                .forEach(callee -> callers.computeIfAbsent(callee, k -> new HashSet<>()).add(caller)));
        Deque<String> queue = new ArrayDeque<>(usingSwt);
        while (!queue.isEmpty()) {
            for (String caller : callers.getOrDefault(queue.poll(), Set.of())) {
                if (usingSwt.add(caller)) {
                    queue.add(caller);
                }
            }
        }
        Set<String> result = new TreeSet<>();
        for (String method : usingSwt) {
            if (method.endsWith("." + CLINIT)) {
                result.add(method.substring(0, method.length() - CLINIT.length() - 1).replace('/', '.'));
            }
        }
        return result;
    }

    private static boolean isSwt(String nameOrDescriptor) {
        for (String swtPackage : SWT_PACKAGES) {
            if (nameOrDescriptor.startsWith(swtPackage) || nameOrDescriptor.contains("L" + swtPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a class file mentions an SWT package anywhere (class names, descriptors, strings) : over-approximates
     * the references of its code.
     */
    static boolean containsSwtPackage(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            byte[][] prefixes = SWT_PACKAGE_BYTES[bytes[i] & 0xff];
            if (prefixes != null) {
                for (byte[] prefix : prefixes) {
                    if (startsWith(bytes, i, prefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int indexOf(byte[] bytes, byte[] part, int from) {
        for (int i = from; i <= bytes.length - part.length; i++) {
            if (startsWith(bytes, i, part)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (offset + prefix.length > bytes.length) {
            return false;
        }
        for (int j = 0; j < prefix.length; j++) {
            if (bytes[offset + j] != prefix[j]) {
                return false;
            }
        }
        return true;
    }

    private static byte[][][] swtPackageBytes() {
        Map<Integer, List<byte[]>> byFirstByte = new HashMap<>();
        for (String swtPackage : SWT_PACKAGES) {
            byte[] prefix = swtPackage.getBytes(StandardCharsets.US_ASCII);
            byFirstByte.computeIfAbsent(prefix[0] & 0xff, b -> new ArrayList<>()).add(prefix);
        }
        byte[][][] table = new byte[256][][];
        byFirstByte.forEach((b, prefixes) -> table[b] = prefixes.toArray(byte[][]::new));
        return table;
    }
}
