package io.quarkiverse.desktop.swt.deployment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The members of the SWT classes that the native code of SWT looks up with JNI, computed from the class files of the SWT
 * jar : they differ between platforms and SWT versions.
 * <ul>
 * <li>The struct classes : the native code reads and writes the fields of the Java objects that mirror the C structs
 * ({@code RECT}, {@code MSG}, {@code GdkRectangle}, {@code NSRect}...) with {@code GetFieldID}, on the class of the object
 * and its super classes. They are the classes of the {@code org.eclipse.swt.internal} packages in the descriptors of the
 * {@code native} methods, with their super classes and the classes of their instance fields (nested structs),
 * registered with all their fields. A missing one fails with a {@code NoSuchFieldError}, or crashes.</li>
 * <li>The callbacks : {@code new Callback(object, "windowProc", 4)} makes the native code call {@code windowProc} with
 * {@code GetMethodID}. Their names are the constant strings given to the constructors of
 * {@code org.eclipse.swt.internal.Callback} (or the prefix of a concatenation : {@code "callback" + i} of
 * {@code COMObject}), and they take primitive arguments (or a {@code long[]}) : every method of the jar with such a name
 * and such a descriptor is registered. A missing one fails with {@code SWTError: No more callbacks}.</li>
 * <li>The Edge callbacks (Windows) : the native code calls {@code Invoke} and {@code CallJava} on the implementations of
 * the {@code ICoreWebView2Swt*} interfaces.</li>
 * </ul>
 */
final class SwtNativeAccess {

    private static final Logger LOGGER = Logger.getLogger(SwtNativeAccess.class);

    static final String INTERNAL_PACKAGE = "org/eclipse/swt/internal/";

    static final String CALLBACK = "org/eclipse/swt/internal/Callback";

    /**
     * The interfaces whose implementations the native code of the Edge browser calls back (Windows).
     */
    static final String WEBVIEW_CALLBACK_INTERFACES = "org/eclipse/swt/internal/ole/win32/ICoreWebView2Swt";

    /**
     * A method reached from native code.
     *
     * @param className the binary name of the declaring class
     * @param name the name of the method
     * @param parameterTypes the Java names of the parameter types ({@code long}, {@code long[]}...)
     */
    record Method(String className, String name, List<String> parameterTypes) implements Comparable<Method> {

        @Override
        public int compareTo(Method other) {
            return toString().compareTo(other.toString());
        }

        @Override
        public String toString() {
            return className + "#" + name + "(" + String.join(",", parameterTypes) + ")";
        }
    }

    /**
     * What the first pass keeps of a class.
     */
    private record ClassSummary(String superName, List<String> interfaces, List<String> instanceFieldTypes,
            List<MethodSummary> methods) {
    }

    private record MethodSummary(String name, String descriptor, int access) {
    }

    private final Map<String, ClassSummary> classes = new HashMap<>();
    private final Set<String> nativeTypes = new TreeSet<>();
    private final Set<String> callbackNames = new TreeSet<>();
    private final Set<String> callbackPrefixes = new TreeSet<>();

    private final Set<String> structClasses = new TreeSet<>();
    private final Set<Method> callbacks = new TreeSet<>();

    private SwtNativeAccess() {
    }

    /**
     * Scans the class files of the SWT jar.
     *
     * @param classFiles gives the internal name and the content of every class file of the jar
     */
    static SwtNativeAccess scan(Consumer<BiConsumer<String, byte[]>> classFiles) {
        SwtNativeAccess access = new SwtNativeAccess();
        classFiles.accept(access::read);
        access.computeStructClasses();
        access.computeCallbacks();
        LOGGER.debugf("SWT native access : %d struct classes, %d callback methods (names %s, prefixes %s)",
                access.structClasses.size(), access.callbacks.size(), access.callbackNames, access.callbackPrefixes);
        return access;
    }

    /**
     * The binary names of the struct classes, to register with all their fields.
     */
    Set<String> structClasses() {
        return structClasses;
    }

    /**
     * The callback methods.
     */
    Set<Method> callbacks() {
        return callbacks;
    }

    private void read(String name, byte[] bytes) {
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {

                private String superName;
                private List<String> interfaces = List.of();
                private final List<String> fieldTypes = new ArrayList<>();
                private final List<MethodSummary> methods = new ArrayList<>();

                @Override
                public void visit(int version, int access, String className, String signature, String superName,
                        String[] interfaces) {
                    this.superName = superName;
                    this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
                }

                @Override
                public FieldVisitor visitField(int access, String fieldName, String descriptor, String signature,
                        Object value) {
                    if ((access & Opcodes.ACC_STATIC) == 0) {
                        fieldTypes.add(descriptor);
                    }
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature,
                        String[] exceptions) {
                    methods.add(new MethodSummary(methodName, descriptor, access));
                    if ((access & Opcodes.ACC_NATIVE) != 0) {
                        Type type = Type.getMethodType(descriptor);
                        nativeType(type.getReturnType());
                        for (Type argument : type.getArgumentTypes()) {
                            nativeType(argument);
                        }
                        return null;
                    }
                    // the constructors of Callback delegate to each other : not callback sites
                    return name.equals(CALLBACK) ? null : new CallbackSites();
                }

                @Override
                public void visitEnd() {
                    classes.put(name, new ClassSummary(superName, interfaces, fieldTypes, methods));
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            // e.g. class file version not supported by ASM
            LOGGER.warnf(e, "Unable to scan the SWT class %s : the native executable may miss some of its JNI metadata",
                    name);
        }
    }

    private void nativeType(Type type) {
        String element = elementClass(type);
        if (element != null && element.startsWith(INTERNAL_PACKAGE)) {
            nativeTypes.add(element);
        }
    }

    /**
     * The internal name of the class of a type, or of its elements for an array type, {@code null} for primitive types.
     */
    private static String elementClass(Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        return element.getSort() == Type.OBJECT ? element.getInternalName() : null;
    }

    /**
     * The names given to the constructors of {@code Callback} : the last constant string, or the prefix of the last
     * string concatenation ({@code "callback" + i}), before the call of the constructor.
     */
    private final class CallbackSites extends MethodVisitor {

        private String lastString;
        private String lastPrefix;

        CallbackSites() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String string) {
                lastString = string;
                lastPrefix = null;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            for (Object argument : bootstrapMethodArguments) {
                // the recipe of StringConcatFactory.makeConcatWithConstants : \1 marks an argument
                if (argument instanceof String recipe && recipe.indexOf('\u0001') > 0) {
                    lastPrefix = recipe.substring(0, recipe.indexOf('\u0001'));
                    lastString = null;
                }
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL && owner.equals(CALLBACK) && name.equals("<init>")) {
                if (lastString != null) {
                    callbackNames.add(lastString);
                } else if (lastPrefix != null) {
                    callbackPrefixes.add(lastPrefix);
                }
                lastString = null;
                lastPrefix = null;
            }
        }
    }

    private void computeStructClasses() {
        Deque<String> queue = new ArrayDeque<>(nativeTypes);
        Set<String> found = new TreeSet<>(nativeTypes);
        while (!queue.isEmpty()) {
            ClassSummary summary = classes.get(queue.poll());
            if (summary == null) {
                continue;
            }
            List<String> next = new ArrayList<>();
            if (summary.superName() != null) {
                next.add(summary.superName());
            }
            for (String fieldType : summary.instanceFieldTypes()) {
                String element = elementClass(Type.getType(fieldType));
                if (element != null) {
                    next.add(element);
                }
            }
            for (String name : next) {
                if (name.startsWith(INTERNAL_PACKAGE) && found.add(name)) {
                    queue.add(name);
                }
            }
        }
        // Callback is passed to its own native methods (bind), the native code reads none of its fields
        found.remove(CALLBACK);
        found.removeIf(name -> !classes.containsKey(name));
        found.forEach(name -> structClasses.add(name.replace('/', '.')));
    }

    private void computeCallbacks() {
        Set<String> webViewInterfaces = new TreeSet<>();
        classes.forEach((name, summary) -> {
            if (name.startsWith(WEBVIEW_CALLBACK_INTERFACES)) {
                webViewInterfaces.add(name);
            }
        });
        classes.forEach((className, summary) -> {
            for (MethodSummary method : summary.methods()) {
                if ((method.access() & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0 && isCallbackName(method.name())
                        && isCallbackDescriptor(method.descriptor())) {
                    callbacks.add(method(className, method));
                }
            }
            // the Edge callbacks : the methods of the interfaces, on their implementations and the interfaces
            for (String implemented : summary.interfaces()) {
                if (webViewInterfaces.contains(implemented)) {
                    for (MethodSummary interfaceMethod : classes.get(implemented).methods()) {
                        for (MethodSummary method : summary.methods()) {
                            if (method.name().equals(interfaceMethod.name())
                                    && method.descriptor().equals(interfaceMethod.descriptor())) {
                                callbacks.add(method(className, method));
                                callbacks.add(method(implemented, interfaceMethod));
                            }
                        }
                    }
                }
            }
        });
    }

    private boolean isCallbackName(String name) {
        if (callbackNames.contains(name)) {
            return true;
        }
        for (String prefix : callbackPrefixes) {
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && name.substring(prefix.length()).chars().allMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a method can be called by a callback : at least one argument, primitive arguments or a single
     * {@code long[]} (array based callbacks), a primitive or {@code void} result.
     */
    static boolean isCallbackDescriptor(String descriptor) {
        Type type = Type.getMethodType(descriptor);
        Type[] arguments = type.getArgumentTypes();
        if (arguments.length == 0 || isReference(type.getReturnType())) {
            return false;
        }
        if (arguments.length == 1 && arguments[0].getDescriptor().equals("[J")) {
            return true;
        }
        for (Type argument : arguments) {
            if (isReference(argument)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static Method method(String className, MethodSummary method) {
        List<String> parameterTypes = new ArrayList<>();
        for (Type argument : Type.getArgumentTypes(method.descriptor())) {
            parameterTypes.add(argument.getClassName());
        }
        return new Method(className.replace('/', '.'), method.name(), parameterTypes);
    }
}
