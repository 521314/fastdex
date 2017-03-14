package com.dx168.fastdex.build.transform

import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import com.dx168.fastdex.build.util.ClassInject
import com.dx168.fastdex.build.util.Constant
import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.GradleUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import com.android.build.api.transform.Format
import com.dx168.fastdex.build.util.FileUtils

/**
 * Created by tong on 17/10/3.
 */
class FastdexTransform extends TransformProxy {
    Project project
    String variantName
    String manifestPath

    FastdexTransform(Transform base, Project project, String variantName,String manifestPath) {
        super(base)
        this.project = project
        this.variantName = variantName
        this.manifestPath = manifestPath
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, IOException, InterruptedException {
        if (FastdexUtils.hasValidCache(project,variantName)) {
            //get patch jar
            File patchJar = generatePatchJar(transformInvocation)

            String dxcmd = "${project.android.getSdkDirectory()}/build-tools/${project.android.getBuildToolsVersion()}/dx"

            File patchDexFile = new File(FastdexUtils.getBuildDir(project,variantName),"patch.dex")
            FileUtils.deleteFile(patchDexFile)
            dxcmd = "${dxcmd} --dex --output=${patchDexFile} ${patchJar}"
            project.logger.error("==fastdex generate dex cmd \n" + dxcmd)
            def process = dxcmd.execute()
            int status = process.waitFor()
            process.destroy()
            if (status == 0) {
                //dexelements [fastdex-runtime.dex fastdex-antilazyload.dex patch.dex ${dex_cache}.listFiles]
                File dexOutputDir = getDexOutputDir(transformInvocation)
                FileUtils.cleanDir(dexOutputDir)
                File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

                //runtime.dex            => classes.dex
                //antilazyload.dex       => classes2.dex
                //patch.dex              => classes3.dex
                //dex_cache.classes.dex  => classes4.dex
                //dex_cache.classes2.dex => classes5.dex

                //copy fastdex-runtime.dex
                FileUtils.copyResourceUsingStream(Constant.RUNTIME_DEX_FILENAME,new File(dexOutputDir,"classes.dex"))
                //copy fastdex-antilazyload.dex
                FileUtils.copyResourceUsingStream(Constant.ANTILAZYLOAD_DEX_FILENAME,new File(dexOutputDir,"classes2.dex"))
                //copy patch.dex
                FileUtils.copyFileUsingStream(patchDexFile,new File(dexOutputDir,"classes3.dex"))
                FileUtils.copyFileUsingStream(new File(cacheDexDir,"classes.dex"),new File(dexOutputDir,"classes4.dex"))

                int point = 2
                File dexFile = new File(cacheDexDir,"classes" + point + ".dex")
                while (FileUtils.isLegalFile(dexFile.getAbsolutePath())) {
                    FileUtils.copyFileUsingStream(dexFile,new File(dexOutputDir,"classes" + (point + 3) + ".dex"))
                    point++
                    dexFile = new File(cacheDexDir,"classes" + point + ".dex")
                }

                //log
                StringBuilder sb = new StringBuilder()
                sb.append("cached_dex[")
                File[] dexFiles = cacheDexDir.listFiles()
                for (File file : dexFiles) {
                    if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                        sb.append(file.getName())
                        if (file != dexFiles[dexFiles.length - 1]) {
                            sb.append(",")
                        }
                    }
                }
                sb.append("] cur-dex[")
                dexFiles = dexOutputDir.listFiles()
                for (File file : dexFiles) {
                    if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                        sb.append(file.getName())
                        if (file != dexFiles[dexFiles.length - 1]) {
                            sb.append(",")
                        }
                    }
                }
                sb.append("]")
                project.logger.error("==fastdex ${sb}")
            }
            else {
                throw new GradleException("==fastdex generate dex fail: \n${dxcmd}")
                //fail
                //base.transform(transformInvocation)
            }
        }
        else {
            //normal build
            File combinedJar = new File(FastdexUtils.getBuildDir(project,variantName),Constant.COMBINED_JAR_FILENAME)
            GradleUtils.executeMerge(transformInvocation,combinedJar)

            File injectedJar = new File(FastdexUtils.getBuildDir(project,variantName),"injected-combined.jar")
            ClassInject.injectJar(project,variantName,combinedJar, injectedJar)

            FileUtils.deleteFile(combinedJar)
            createSourceSetSnapshoot()
            keepDependenciesList()
            //invoke the original transform method
            base.transform(GradleUtils.createNewTransformInvocation(this,transformInvocation,injectedJar))
            //save dex
            copyNormalBuildDex(transformInvocation)
            //save R.txt
            copyRTxt()
        }
    }

    File generatePatchJar(TransformInvocation transformInvocation) {
        //compare changed class
        Set<String> changedJavaClassNames = FastdexUtils.scanChangedClasses(project,variantName,manifestPath)
        //add all changed file to jar
        File mergedJar = new File(FastdexUtils.getBuildDir(project,variantName),"latest-merged.jar")

        FileUtils.deleteFile(mergedJar)
        GradleUtils.executeMerge(transformInvocation,mergedJar)

        File classesDir = new File(FastdexUtils.getBuildDir(project,variantName),"patch-" + Constant.FASTDEX_CLASSES_DIR)
        FileUtils.deleteDir(classesDir)
        FileUtils.ensumeDir(classesDir)

        project.copy {
            from project.zipTree(mergedJar)
            for (String className : changedJavaClassNames) {
                include className
            }

            into classesDir
        }
        FileUtils.deleteFile(mergedJar)

        File patchJar = new File(FastdexUtils.getBuildDir(project,variantName),"patch-combined.jar")
        project.ant.zip(baseDir: classesDir, destFile: patchJar)

        FileUtils.deleteDir(classesDir)

        project.logger.error("==fastdex will generate dex file ${changedJavaClassNames}")

        return patchJar
    }

    void copyRTxt() {
        File sourceFile = new File(project.getBuildDir(),"/intermediates/symbols/${variantName}/R.txt")
        File destFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.R_TXT)
        FileUtils.copyFileUsingStream(sourceFile,destFile)
    }

    void createSourceSetSnapshoot() {
        String[] srcDirs = project.android.sourceSets.main.java.srcDirs
        File snapshootDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_SNAPSHOOT_DIR)
        FileUtils.ensumeDir(snapshootDir)
        for (String srcDir : srcDirs) {
//            project.copy {
//                from(new File(srcDir))
//                into(new File(snapshootDir,srcDir))
//            }

            FileUtils.copyDir(new File(srcDir),new File(snapshootDir,srcDir),Constant.JAVA_SUFFIX)
        }
    }

    void keepDependenciesList() {
        Set<String> dependenciesList = GradleUtils.getCurrentDependList(project,variantName)
        StringBuilder sb = new StringBuilder()
        dependenciesList.each {
            sb.append(it)
            sb.append("\n")
        }

        File dependenciesListFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.DEPENDENCIES_MAPPING_FILENAME);
        FileUtils.write2file(sb.toString().getBytes(),dependenciesListFile)
    }

    File getDexOutputDir(TransformInvocation transformInvocation) {
        def outputProvider = transformInvocation.getOutputProvider();
        File outputDir = null;
        try {
            outputDir = outputProvider.getContentLocation("main", base.getOutputTypes(), base.getScopes(), Format.DIRECTORY);
        } catch (Throwable e) {
            e.printStackTrace()
        }

        return outputDir;
    }

    void copyNormalBuildDex(TransformInvocation transformInvocation) {
        File dexOutputDir = getDexOutputDir(transformInvocation)

        project.logger.error("==fastdex dex output directory: " + dexOutputDir)

        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)
        File[] files = dexOutputDir.listFiles()
        files.each { file ->
            if (file.getName().endsWith(".dex")) {
                FileUtils.copyFileUsingStream(file,new File(cacheDexDir,file.getName()))
            }
        }

        //dexelements [fastdex-runtime.dex fastdex-antilazyload.dex ${dex_cache}.listFiles]
        FileUtils.cleanDir(dexOutputDir)

        //runtime.dex            => classes.dex
        //antilazyload.dex       => classes2.dex
        //dex_cache.classes.dex  => classes3.dex
        //dex_cache.classes2.dex => classes4.dex

        //copy fastdex-runtime.dex
        FileUtils.copyResourceUsingStream(Constant.RUNTIME_DEX_FILENAME,new File(dexOutputDir,"classes.dex"))
        //copy fastdex-antilazyload.dex
        FileUtils.copyResourceUsingStream(Constant.ANTILAZYLOAD_DEX_FILENAME,new File(dexOutputDir,"classes2.dex"))

        FileUtils.copyFileUsingStream(new File(cacheDexDir,"classes.dex"),new File(dexOutputDir,"classes3.dex"))
        int point = 2
        File dexFile = new File(cacheDexDir,"classes" + point + ".dex")
        while (FileUtils.isLegalFile(dexFile.getAbsolutePath())) {
            FileUtils.copyFileUsingStream(dexFile,new File(dexOutputDir,"classes" + (point + 2) + ".dex"))
            point++
            dexFile = new File(cacheDexDir,"classes" + point + ".dex")
        }

        //log
        StringBuilder sb = new StringBuilder()
        sb.append("cached_dex[")
        File[] dexFiles = cacheDexDir.listFiles()
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (file != dexFiles[dexFiles.length - 1]) {
                    sb.append(",")
                }
            }
        }
        sb.append("] cur-dex[")
        dexFiles = dexOutputDir.listFiles()
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (file != dexFiles[dexFiles.length - 1]) {
                    sb.append(",")
                }
            }
        }
        sb.append("]")
        project.logger.error("==fastdex first build ${sb}")
    }
}