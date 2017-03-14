package com.dx168.fastdex.build.util

import org.gradle.api.Project
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Created by tong on 17/3/12.
 */
public class ClassInjectFileVisitor extends SimpleFileVisitor<Path> {
    Project project
    Set<String> sourceSetJavaFiles
    Path scanPath
    Path outputPath

    ClassInjectFileVisitor(Project project,Set<String> sourceSetJavaFiles,Path scanPath,Path outputPath) {
        this.project = project
        this.sourceSetJavaFiles = sourceSetJavaFiles
        this.scanPath = scanPath
        this.outputPath = outputPath
    }

    @Override
    FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (scanPath.equals(dir)) {
            return FileVisitResult.CONTINUE;
        }
        Path relativePath = scanPath.relativize(dir)
        Path packagePath = outputPath.resolve(relativePath)
        boolean result = FileUtils.ensumeDir(packagePath.toFile())
        if (!result) {
            project.logger.error("==fastdex create folder fail: " + packagePath.toFile())
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!file.toFile().getName().endsWith(Constant.CLASS_SUFFIX)) {
            return FileVisitResult.CONTINUE;
        }
        Path relativePath = scanPath.relativize(file)
        Path classFilePath = outputPath.resolve(relativePath)

        if (matchSourceSetJavaFile(relativePath) && !isBalckList(relativePath.toString())) {
            project.logger.error("==fastdex inject: " + file)
            ClassInject.inject(file.toFile(),classFilePath.toFile())
        }
        else {
            FileUtils.copyFileUsingStream(file.toFile(),classFilePath.toFile())
        }
        return FileVisitResult.CONTINUE;
    }

    boolean isBalckList(String classFile) {
        //TODO
        return false
    }

    boolean matchSourceSetJavaFile(Path relativePath) {
        //relativePath  like   com.dx168.fastdex.sample.MainActivity.class
        String className = relativePath.toString()
        className = className.substring(0,className.length() - Constant.CLASS_SUFFIX.length())
        //className => com.dx168.fastdex.sample.MainActivity
        boolean result = sourceSetJavaFiles.contains(className)

        // || className.startsWith("android/support/multidex/")
        return result
    }
}