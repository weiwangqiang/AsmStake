package com.trace.plugin;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.AppExtension;
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import com.trace.plugin.tools.TraceBuildConfig
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.utils.IOUtils
import org.codehaus.groovy.runtime.DefaultGroovyMethods
import org.codehaus.groovy.runtime.ResourceGroovyMethods;
import org.objectweb.asm.*

import org.gradle.api.Plugin;
import org.gradle.api.Project

import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry;

/**
 * Created by weiwangqiang on 2023/4/2
 */
public class TraceFixPlugin extends Transform implements Plugin<Project> {
    private TraceBuildConfig mTraceBuildConfig

    @Override
    public void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation);
        long startTime = System.currentTimeMillis()
        doTransform(transformInvocation)
        System.out.println("spend " + (System.currentTimeMillis() - startTime))
    }

    private void doTransform(TransformInvocation transformInvocation) {
        DefaultGroovyMethods.each()
        //拿到所有的class文件
        Collection<TransformInput> inputs = transformInvocation.getInputs();
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        if (outputProvider != null) {
            outputProvider.deleteAll()
        }
        //遍历inputs Transform的inputs有两种类型，一种是目录，一种是jar包，要分开遍历
        for (TransformInput input : inputs) {
            Collection<DirectoryInput> directoryInputs = input.getDirectoryInputs();
            for (DirectoryInput directoryInput : directoryInputs) {
                handDirectoryInput(directoryInput, outputProvider);
            }
            Collection<JarInput> jarInputs = input.getJarInputs();
            for (JarInput jarInput : jarInputs) {
                handJarInput(jarInput, outputProvider);
            }
        }
    }


    //遍历directoryInputs  得到对应的class  交给ASM处理
    private void handDirectoryInput(DirectoryInput input, TransformOutputProvider outputProvider) {
        //是否是文件夹
        if (input.file.isDirectory()) {
            //列出目录所有文件（包含子文件夹，子文件夹内文件）
            input.file.eachFileRecurse { File file ->
                String name = file.name
                //需要插桩class 根据自己的需求来------------- 这里判断是否是我们自己写的Application
                if (TraceBuildConfig.isNeedTraceClass(name)) {
                    handleFile(file)
                }
            }
        }
        //处理完输入文件后把输出传给下一个文件
        def dest = outputProvider.getContentLocation(input.name, input.contentTypes, input.scopes, Format.DIRECTORY)
        FileUtils.copyDirectory(input.file, dest)
    }
    //遍历jarInputs 得到对应的class 交给ASM处理
    private void handJarInput(JarInput jarInput, TransformOutputProvider outputProvider) {
        if (jarInput.file.getAbsolutePath().endsWith(".jar")) {
            //重名名输出文件,因为可能同名,会覆盖
            def jarName = jarInput.name
            def md5Name = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
            if (jarName.endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length() - 4)
            }
            JarFile jarFile = new JarFile(jarInput.file)
            Enumeration enumeration = jarFile.entries()
            File tmpFile = new File(jarInput.file.getParent() + File.separator + "classes_temp.jar")
            //避免上次的缓存被重复插入
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tmpFile))
            //用于保存
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement()
                String entryName = jarEntry.getName()
                ZipEntry zipEntry = new ZipEntry(entryName)
                InputStream inputStream = jarFile.getInputStream(jarEntry)
                //需要插桩class 根据自己的需求来-------------
                if (TraceBuildConfig.isNeedTraceClass(entryName)) {
                    //class文件处理
                    println '----------- jar class  <' + entryName + '> -----------'
                    jarOutputStream.putNextEntry(zipEntry)
                    ClassReader classReader = new ClassReader(IOUtils.toByteArray(inputStream))
                    ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
                    //创建类访问器   并交给它去处理
                    ClassVisitor cv = new TraceFixMethodTracer(Opcodes.ASM6, classWriter)
                    classReader.accept(cv, ClassReader.EXPAND_FRAMES)
                    byte[] code = classWriter.toByteArray()
                    jarOutputStream.write(code)
                } else {
                    jarOutputStream.putNextEntry(zipEntry)
                    jarOutputStream.write(IOUtils.toByteArray(inputStream))
                }
                jarOutputStream.closeEntry()
            }
            //结束
            jarOutputStream.close()
            jarFile.close()
            //获取output目录
            def dest = outputProvider.getContentLocation(jarName + md5Name,
                    jarInput.contentTypes, jarInput.scopes, Format.JAR)
            FileUtils.copyFile(tmpFile, dest)
            tmpFile.delete()
        }
    }

    private void handleFile(File file) {
        ClassReader cr = new ClassReader(ResourceGroovyMethods.getBytes(file))
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS)
        ClassVisitor classVisitor = new TraceFixMethodTracer(Opcodes.ASM6, cw)
        cr.accept(classVisitor, ClassReader.EXPAND_FRAMES)
        byte[] bytes = cw.toByteArray()
        FileOutputStream fos = new FileOutputStream(file.parentFile.absolutePath + File.separator + file.name)
        fos.write(bytes)
        fos.close()
    }


    @Override
    public String getName() {
        return "TraceFixPlugin";
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_JARS;
    }

    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void apply(Project project) {
        project.getExtensions().findByType(AppExtension.class).registerTransform(this);
        mTraceBuildConfig = new TraceBuildConfig();
    }
}
