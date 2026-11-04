package dev.oqthara.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits straight-line JVM bytecode for a deliberately small subset of Oqthara:
 * {@code print <arithmetic expression>} and {@code write <expression>}, where expressions are
 * integer/decimal/text constants, {@code + - * / %}, unary minus and parentheses.
 *
 * <p>Everything else is refused with a {@link NotSupportedException}, so the caller can fall
 * back to the interpreter-launcher jar. Refusing is a feature: a compiled jar that behaves
 * differently from {@code oq run} would be worse than no jar at all.
 *
 * <p>There are no branches, so no {@code StackMapTable} is required and the JVM verifier
 * accepts the class without stack-map frames.
 */
public final class BytecodeCompiler {
    private static final int JAVA_21_MAJOR = 65;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;

    /** Thrown when a program uses a construct outside the compiled subset. */
    public static final class NotSupportedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotSupportedException(String reason) {
            super(reason);
        }
    }

    private BytecodeCompiler() {
    }

    /** True when the IR can be compiled to real bytecode. */
    public static boolean canCompile(Ir.Program program) {
        try {
            compile(program, "oq.generated.Program");
            return true;
        } catch (NotSupportedException e) {
            return false;
        }
    }

    /** Human-readable reason a program cannot be compiled, or {@code null} when it can. */
    public static String whyNot(Ir.Program program) {
        try {
            compile(program, "oq.generated.Program");
            return null;
        } catch (NotSupportedException e) {
            return e.getMessage();
        }
    }

    /**
     * Compiles the program into a class with {@code public static void main(String[])} that
     * prints exactly what the interpreter would print.
     */
    public static byte[] compile(Ir.Program program, String className) {
        if (!program.functions().isEmpty()) {
            throw new NotSupportedException("functions are not compiled yet");
        }
        ConstantPool pool = new ConstantPool();
        List<byte[]> body = new ArrayList<>();
        for (Ir.Instr instr : program.main().body()) {
            emit(instr, pool, body);
        }
        body.add(new byte[]{(byte) 0xB1}); // return
        byte[] code = concat(body);

        int thisClass = pool.classRef(pool.utf8(className.replace('.', '/')));
        int objectClass = pool.classRef(pool.utf8("java/lang/Object"));
        int mainName = pool.utf8("main");
        int mainDescriptor = pool.utf8("([Ljava/lang/String;)V");
        int codeAttribute = pool.utf8("Code");
        int sourceFile = pool.utf8("SourceFile");
        int fileName = pool.utf8(className.substring(className.lastIndexOf('.') + 1) + ".java");

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(JAVA_21_MAJOR);
            pool.write(out);
            out.writeShort(ACC_PUBLIC);
            out.writeShort(thisClass);
            out.writeShort(objectClass);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(1);
            out.writeShort(ACC_PUBLIC | ACC_STATIC);
            out.writeShort(mainName);
            out.writeShort(mainDescriptor);
            out.writeShort(1);
            out.writeShort(codeAttribute);
            out.writeInt(12 + code.length);
            out.writeShort(64);   // max_stack: generous, verified by the JVM
            out.writeShort(1);    // max_locals: the args array
            out.writeInt(code.length);
            out.write(code);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(1);
            out.writeShort(sourceFile);
            out.writeInt(2);
            out.writeShort(fileName);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void emit(Ir.Instr instr, ConstantPool pool, List<byte[]> body) {
        switch (instr) {
            case Ir.Const constant -> emitConstant(constant.value(), pool, body);
            case Ir.Binary binary -> emitBinary(binary.op(), pool, body);
            case Ir.Unary unary -> emitUnary(unary.op(), pool, body);
            case Ir.Pop ignored -> body.add(new byte[]{(byte) 0x57}); // pop
            case Ir.Halt ignored -> {
                // reached only at the very end; the trailing return covers it
            }
            case Ir.Call call -> emitCall(call, pool, body);
            default -> throw new NotSupportedException(
                    "instruction " + instr.getClass().getSimpleName() + " is not compiled yet");
        }
    }

    private static void emitConstant(Object value, ConstantPool pool, List<byte[]> body) {
        if (value == null) {
            body.add(new byte[]{(byte) 0x01}); // aconst_null
            return;
        }
        if (value instanceof Long number) {
            int index = pool.longRef(number);
            body.add(new byte[]{0x14, hi(index), lo(index)}); // ldc2_w
            int ref = pool.methodRef("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
            body.add(new byte[]{(byte) 0xB8, hi(ref), lo(ref)});
            return;
        }
        if (value instanceof Double number) {
            int index = pool.doubleRef(number);
            body.add(new byte[]{0x14, hi(index), lo(index)}); // ldc2_w
            body.add(new byte[]{(byte) 0xB8,
                    hi(pool.methodRef("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;")),
                    lo(pool.methodRef("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"))});
            return;
        }
        if (value instanceof Boolean bool) {
            int ref = pool.methodRef("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
            body.add(new byte[]{(byte) (bool ? 0x04 : 0x03)}); // iconst_1 / iconst_0
            body.add(new byte[]{(byte) 0xB8, hi(ref), lo(ref)});
            return;
        }
        if (value instanceof String text) {
            int index = pool.string(text);
            body.add(new byte[]{0x13, hi(index), lo(index)}); // ldc_w
            return;
        }
        throw new NotSupportedException("constant of type " + value.getClass().getSimpleName()
                + " is not compiled yet");
    }

    private static void emitBinary(String op, ConstantPool pool, List<byte[]> body) {
        String name = switch (op) {
            case "+" -> "add";
            case "-" -> "subtract";
            case "*" -> "multiply";
            case "/" -> "divide";
            case "%" -> "modulo";
            default -> throw new NotSupportedException(
                    "operator '" + op + "' is not compiled yet (only + - * / % are)");
        };
        int ref = pool.methodRef("dev/oqthara/runtime/Arithmetic", name,
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        body.add(new byte[]{(byte) 0xB8, hi(ref), lo(ref)});
    }

    private static void emitUnary(String op, ConstantPool pool, List<byte[]> body) {
        if (!op.equals("-")) {
            throw new NotSupportedException("unary '" + op + "' is not compiled yet");
        }
        int ref = pool.methodRef("dev/oqthara/runtime/Arithmetic", "negate",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        body.add(new byte[]{(byte) 0xB8, hi(ref), lo(ref)});
    }

    private static void emitCall(Ir.Call call, ConstantPool pool, List<byte[]> body) {
        if (!call.name().equals("print") || call.argc() != 1) {
            throw new NotSupportedException(
                    "call to '" + call.name() + "' with " + call.argc()
                            + " argument(s) is not compiled yet (only print/1 is)");
        }
        int ref = pool.methodRef("dev/oqthara/runtime/Arithmetic", "print",
                "(Ljava/lang/Object;)V");
        body.add(new byte[]{(byte) 0xB8, hi(ref), lo(ref)});
    }

    private static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static byte hi(int value) {
        return (byte) ((value >>> 8) & 0xFF);
    }

    private static byte lo(int value) {
        return (byte) (value & 0xFF);
    }

    /** Constant pool with the entry kinds this compiler needs. */
    private static final class ConstantPool {
        private static final int TAG_UTF8 = 1;
        private static final int TAG_INTEGER = 3;
        private static final int TAG_LONG = 5;
        private static final int TAG_DOUBLE = 6;
        private static final int TAG_CLASS = 7;
        private static final int TAG_STRING = 8;
        private static final int TAG_FIELDREF = 9;
        private static final int TAG_METHODREF = 10;
        private static final int TAG_NAME_AND_TYPE = 12;

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream data = new DataOutputStream(bytes);
        private int next = 1;

        int utf8(String text) {
            byte[] encoded = ClassFileWriter.modifiedUtf8(text);
            if (encoded.length > 65535) throw new IllegalArgumentException("constant too long");
            write(TAG_UTF8, () -> {
                data.writeShort(encoded.length);
                data.write(encoded);
            });
            return next - 1;
        }

        int classRef(int nameIndex) {
            write(TAG_CLASS, () -> data.writeShort(nameIndex));
            return next - 1;
        }

        int string(String text) {
            int utf8 = utf8(text);
            write(TAG_STRING, () -> data.writeShort(utf8));
            return next - 1;
        }

        int longRef(long value) {
            write(TAG_LONG, () -> data.writeLong(value));
            next++; // longs take two constant-pool slots
            return next - 2;
        }

        int doubleRef(double value) {
            write(TAG_DOUBLE, () -> data.writeDouble(value));
            next++; // doubles take two constant-pool slots
            return next - 2;
        }

        int methodRef(String owner, String name, String descriptor) {
            int ownerIndex = classRef(utf8(owner));
            int nameIndex = utf8(name);
            int descriptorIndex = utf8(descriptor);
            int nameAndType = writeAndGet(TAG_NAME_AND_TYPE, () -> {
                data.writeShort(nameIndex);
                data.writeShort(descriptorIndex);
            });
            write(TAG_METHODREF, () -> {
                data.writeShort(ownerIndex);
                data.writeShort(nameAndType);
            });
            return next - 1;
        }

        private interface Writer {
            void write() throws IOException;
        }

        private void write(int tag, Writer writer) {
            try {
                data.writeByte(tag);
                writer.write();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            next++;
        }

        private int writeAndGet(int tag, Writer writer) {
            write(tag, writer);
            return next - 1;
        }

        void write(DataOutputStream out) throws IOException {
            data.flush();
            out.writeShort(next);
            bytes.writeTo(out);
        }
    }
}
