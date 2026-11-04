package dev.oqthara.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Emits a JVM class file without any external library, so it works on every supported JDK
 * including 21, where {@code java.lang.classfile} does not exist yet.
 *
 * <p>The generated program is a real class with a real {@code main} method whose bytecode
 * is emitted here: it loads the Oqthara source as a string constant and calls the embedded
 * runner. That is deliberately not ahead-of-time compilation of the program itself; see
 * {@code docs/spec/backend.md} for the exact boundary.
 */
public final class ClassFileWriter {
    private static final int MAGIC = 0xCAFEBABE;
    private static final int JAVA_21_MAJOR = 65;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;

    private ClassFileWriter() {
    }

    /**
     * Builds a class named {@code className} with:
     * {@code public static void main(String[] args) { EmbeddedMain.run(source); }}
     */
    public static byte[] programClass(String className, String source) {
        if (modifiedUtf8(source).length > 65535) {
            throw new IllegalArgumentException(
                    "program source is too large for one constant pool entry");
        }
        ConstantPool pool = new ConstantPool();
        int objectUtf8 = pool.utf8("java/lang/Object");
        int objectClass = pool.classRef(objectUtf8);
        int runnerUtf8 = pool.utf8("dev/oqthara/cli/EmbeddedMain");
        int runnerClass = pool.classRef(runnerUtf8);
        int runName = pool.utf8("runWithArgs");
        int runDescriptor = pool.utf8("(Ljava/lang/String;[Ljava/lang/String;)V");
        int runNameAndType = pool.nameAndType(runName, runDescriptor);
        int runMethod = pool.methodRef(runnerClass, runNameAndType);
        int thisUtf8 = pool.utf8(className.replace('.', '/'));
        int thisClass = pool.classRef(thisUtf8);
        int mainName = pool.utf8("main");
        int mainDescriptor = pool.utf8("([Ljava/lang/String;)V");
        int codeAttr = pool.utf8("Code");
        int sourceIndex = pool.string(source);
        int sourceFileAttr = pool.utf8("SourceFile");
        int sourceFileName = pool.utf8(className.substring(className.lastIndexOf('.') + 1) + ".java");

        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(new byte[]{0x13, hi(sourceIndex), lo(sourceIndex)});   // ldc_w source
        code.writeBytes(new byte[]{0x2A});                                     // aload_0 (String[] args)
        code.writeBytes(new byte[]{(byte) 0xB8, hi(runMethod), lo(runMethod)}); // invokestatic
        code.writeBytes(new byte[]{(byte) 0xB1});                              // return
        byte[] codeBytes = code.toByteArray();

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(MAGIC);
            out.writeShort(0);              // minor
            out.writeShort(JAVA_21_MAJOR);  // major
            pool.write(out);
            out.writeShort(ACC_PUBLIC);     // class access
            out.writeShort(thisClass);
            out.writeShort(objectClass);
            out.writeShort(0);              // interfaces
            out.writeShort(0);              // fields
            out.writeShort(1);              // methods
            out.writeShort(ACC_PUBLIC | ACC_STATIC);
            out.writeShort(mainName);
            out.writeShort(mainDescriptor);
            out.writeShort(1);              // attributes on the method
            out.writeShort(codeAttr);
            // max_stack(2) + max_locals(2) + code_length(4) + code(n) + exception_table(2) + attributes(2)
            out.writeInt(12 + codeBytes.length);
            out.writeShort(2);              // max_stack
            out.writeShort(1);              // max_locals (the args array)
            out.writeInt(codeBytes.length);
            out.write(codeBytes);
            out.writeShort(0);              // exception table
            out.writeShort(0);              // nested code attributes
            out.writeShort(1);              // class attributes
            out.writeShort(sourceFileAttr);
            out.writeInt(2);
            out.writeShort(sourceFileName);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * JVMS 4.4.7 requires "modified UTF-8" in the constant pool: NUL is encoded as two bytes
     * and supplementary characters are stored as two surrogate code units, each encoded
     * separately. Plain UTF-8 produces an illegal constant pool for emoji and similar input.
     */
    static byte[] modifiedUtf8(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                out.write(c);
            } else if (c == 0x0000 || (c >= 0x0080 && c <= 0x07FF)) {
                out.write(0xC0 | ((c >> 6) & 0x1F));
                out.write(0x80 | (c & 0x3F));
            } else {
                // A supplementary character appears as two chars (surrogates) and is encoded
                // twice by this branch, which is exactly what modified UTF-8 requires.
                out.write(0xE0 | ((c >> 12) & 0x0F));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        return out.toByteArray();
    }

    private static byte hi(int value) {
        return (byte) ((value >>> 8) & 0xFF);
    }

    private static byte lo(int value) {
        return (byte) (value & 0xFF);
    }

    /** A minimal constant pool: only the entries the generated class needs. */
    private static final class ConstantPool {
        private static final int TAG_UTF8 = 1;
        private static final int TAG_CLASS = 7;
        private static final int TAG_STRING = 8;
        private static final int TAG_METHODREF = 10;
        private static final int TAG_NAME_AND_TYPE = 12;

        private final ByteArrayOutputStream entries = new ByteArrayOutputStream();
        private final DataOutputStream data = new DataOutputStream(entries);
        private int nextIndex = 1;

        int utf8(String text) {
            byte[] bytes = modifiedUtf8(text);
            if (bytes.length > 65535) throw new IllegalArgumentException("constant too long");
            try {
                data.writeByte(TAG_UTF8);
                data.writeShort(bytes.length);
                data.write(bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return nextIndex++;
        }

        int classRef(int nameIndex) {
            return entry(TAG_CLASS, nameIndex);
        }

        int string(String text) {
            return entry(TAG_STRING, utf8(text));
        }

        int nameAndType(int nameIndex, int descriptorIndex) {
            try {
                data.writeByte(TAG_NAME_AND_TYPE);
                data.writeShort(nameIndex);
                data.writeShort(descriptorIndex);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return nextIndex++;
        }

        int methodRef(int classIndex, int nameAndTypeIndex) {
            try {
                data.writeByte(TAG_METHODREF);
                data.writeShort(classIndex);
                data.writeShort(nameAndTypeIndex);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return nextIndex++;
        }

        private int entry(int tag, int index) {
            try {
                data.writeByte(tag);
                data.writeShort(index);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return nextIndex++;
        }

        void write(DataOutputStream out) throws IOException {
            data.flush();
            out.writeShort(nextIndex);
            entries.writeTo(out);
        }
    }
}
