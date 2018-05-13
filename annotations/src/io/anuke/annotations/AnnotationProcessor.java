package io.anuke.annotations;

import com.squareup.javapoet.*;
import io.anuke.annotations.Annotations.Local;
import io.anuke.annotations.Annotations.Remote;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({
    "io.anuke.annotations.Annotations.Remote",
    "io.anuke.annotations.Annotations.Local"
})
public class AnnotationProcessor extends AbstractProcessor {
    private static final int maxPacketSize = 128;
    private static final String fullClassName = "io.anuke.mindustry.gen.CallEvent";
    private static final String className = fullClassName.substring(1 + fullClassName.lastIndexOf('.'));
    private static final String packageName = fullClassName.substring(0, fullClassName.lastIndexOf('.'));
    private static final HashMap<String, String[][]> writeMap = new HashMap<String, String[][]>(){{
        put("Player", new String[][]{
            {
                "rbuffer.putInt(rvalue.id)"
            },
            {
                "rtype rvalue = io.anuke.mindustry.Vars.playerGroup.getByID(buffer.getInt())"
            }
        });
    }};

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;
    private boolean done;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if(done) return false;
        done = true;

        ArrayList<Element> elements = new ArrayList<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(Remote.class)) {
            if(!element.getModifiers().contains(Modifier.STATIC)) {
                messager.printMessage(Kind.ERROR, "All local/remote methods must be static: ", element);
            }else if(element.getKind() != ElementKind.METHOD){
                    messager.printMessage(Kind.ERROR, "All local/remote annotations must be on methods: ", element);
            }else{
                elements.add(element);
            }
        }

        try {

            TypeSpec.Builder classBuilder = TypeSpec.classBuilder(className)
                    .addModifiers(Modifier.PUBLIC);

            int id = 0;

            classBuilder.addField(FieldSpec.builder(ByteBuffer.class, "TEMP_BUFFER", Modifier.STATIC, Modifier.PRIVATE, Modifier.FINAL)
                    .initializer("ByteBuffer.allocate($1L)", maxPacketSize).build());

            MethodSpec.Builder readMethod = MethodSpec.methodBuilder("readPacket")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(ByteBuffer.class, "buffer")
                    .addParameter(int.class, "id")
                    .returns(void.class);

            CodeBlock.Builder writeSwitch = CodeBlock.builder();
            boolean started = false;

            readMethod.addJavadoc("This method reads and executes a method by ID. For internal use only!");

            for (Element e : elements) {
                boolean local = e.getAnnotation(Local.class) != null;

                ExecutableElement exec = (ExecutableElement)e;

                MethodSpec.Builder method = MethodSpec.methodBuilder(e.getSimpleName().toString())
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(void.class);

                for(VariableElement var : exec.getParameters()){
                    method.addParameter(TypeName.get(var.asType()), var.getSimpleName().toString());
                }

                if(local){
                    //todo
                    int index = 0;
                    StringBuilder results = new StringBuilder();
                    for(VariableElement var : exec.getParameters()){
                        results.append(var.getSimpleName());
                        if(index != exec.getParameters().size() - 1) results.append(", ");
                        index ++;
                    }

                    method.addStatement("$N." + exec.getSimpleName() + "(" + results.toString() + ")",
                            ((TypeElement)e.getEnclosingElement()).getQualifiedName().toString());
                }

                if(!started){
                    writeSwitch.beginControlFlow("if(id == "+id+")");
                }else{
                    writeSwitch.nextControlFlow("else if(id == "+id+")");
                }
                started = true;

                method.addStatement("$1N packet = new $1N()", "io.anuke.mindustry.net.Packets.InvokePacket");
                method.addStatement("packet.writeBuffer = TEMP_BUFFER");
                method.addStatement("TEMP_BUFFER.position(0)");

                for(VariableElement var : exec.getParameters()){
                    String varName = var.getSimpleName().toString();
                    String typeName = var.asType().toString();
                    String bufferName = "TEMP_BUFFER";
                    String simpleTypeName = typeName.contains(".") ? typeName.substring(1 + typeName.lastIndexOf('.')) : typeName;
                    String capName = simpleTypeName.equals("byte") ? "" : Character.toUpperCase(simpleTypeName.charAt(0)) + simpleTypeName.substring(1);

                    if(typeUtils.isAssignable(var.asType(), elementUtils.getTypeElement("java.lang.Enum").asType())) {
                        method.addStatement(bufferName + ".put(" + varName + ".ordinal())");
                    }else if(isPrimitive(typeName)) {
                        if(simpleTypeName.equals("boolean")){
                            method.addStatement(bufferName + ".put(" + varName + " ? (byte)1 : 0)");
                        }else{
                            method.addStatement(bufferName + ".put" +
                                    capName + "(" + varName + ")");
                        }
                    }else if(writeMap.get(simpleTypeName) != null){
                        String[] values = writeMap.get(simpleTypeName)[0];
                        for(String str : values){
                            method.addStatement(str.replaceAll("rbuffer", bufferName)
                            .replaceAll("rvalue", varName));
                        }
                    }else{
                        messager.printMessage(Kind.ERROR, "No method for writing type: " + typeName, var);
                    }

                    if(typeUtils.isAssignable(var.asType(), elementUtils.getTypeElement("java.lang.Enum").asType())) {
                        writeSwitch.addStatement(typeName + " " + varName + " = " + typeName + ".values()["+bufferName +".getInt()]");
                    }else if(isPrimitive(typeName)) {
                        if(simpleTypeName.equals("boolean")){
                            writeSwitch.addStatement("boolean " + varName + " = " + bufferName + ".get() == 1");
                        }else{
                            writeSwitch.addStatement(typeName + " " + varName + " = " + bufferName + ".get" + capName + "()");
                        }
                    }else if(writeMap.get(simpleTypeName) != null){
                        String[] values = writeMap.get(simpleTypeName)[1];
                        for(String str : values){
                            writeSwitch.addStatement(str.replaceAll("rbuffer", bufferName)
                                    .replaceAll("rvalue", varName)
                                    .replaceAll("rtype", simpleTypeName));
                        }
                    }else{
                        messager.printMessage(Kind.ERROR, "No method for writing type: " + typeName, var);
                    }
                }
                method.addStatement("packet.writeLength = TEMP_BUFFER.position()");
                method.addStatement("io.anuke.mindustry.net.Net.send(packet, io.anuke.mindustry.net.Net.SendMode.tcp)");

                classBuilder.addMethod(method.build());

                FieldSpec var = FieldSpec.builder(TypeName.INT, "ID_METHOD_" + exec.getSimpleName().toString().toUpperCase())
                        .initializer("$1L", id).addModifiers(Modifier.FINAL, Modifier.PRIVATE, Modifier.STATIC).build();

                classBuilder.addField(var);

                int index = 0;
                StringBuilder results = new StringBuilder();
                for(VariableElement writevar : exec.getParameters()){
                    results.append(writevar.getSimpleName());
                    if(index != exec.getParameters().size() - 1) results.append(", ");
                    index ++;
                }

                writeSwitch.addStatement("com.badlogic.gdx.Gdx.app.postRunnable(() -> $N." + exec.getSimpleName() + "(" + results.toString() + "))",
                        ((TypeElement)e.getEnclosingElement()).getQualifiedName().toString());

                id ++;

                //TODO add params from the method and invoke it
            }

            if(started){
                writeSwitch.endControlFlow();
            }

            readMethod.addCode(writeSwitch.build());
            classBuilder.addMethod(readMethod.build());

            TypeSpec spec = classBuilder.build();

            JavaFile.builder(packageName, spec).build().writeTo(filer);


        }catch (Exception e){
            throw new RuntimeException(e);
        }

        return true;
    }

    private boolean isPrimitive(String type){
        return type.equals("boolean") || type.equals("byte") || type.equals("short") || type.equals("int")
                || type.equals("long") || type.equals("float") || type.equals("double") || type.equals("char");

    }

}
