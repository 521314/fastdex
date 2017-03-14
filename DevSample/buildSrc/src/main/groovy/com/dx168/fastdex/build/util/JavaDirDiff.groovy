package com.dx168.fastdex.build.util

import org.gradle.api.logging.Logger
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Created by tong on 17/3/10.
 */
public class JavaDirDiff {
    public static Set<String> diff(File newDir, File oldDir, boolean useRelativePath, Logger logger) throws IOException {
        if (newDir == null || oldDir == null) {
            throw new RuntimeException("newDir == null || oldDir == null");
        }
        if (!newDir.isDirectory()) {
            throw new RuntimeException(newDir + " is not dir");
        }
//        if (!oldDir.isDirectory()) {
//            throw new RuntimeException(oldDir + " is not dir");
//        }

        logger.error("==fastdex diff dir: ${newDir} ${oldDir}")
        Set<String> result = new HashSet<>();
        Files.walkFileTree(newDir.toPath(),new CompareFileVisitor(newDir.toPath(),oldDir.toPath(),result,useRelativePath,logger));
        return result;
    }

    private static final class CompareFileVisitor extends SimpleFileVisitor<Path> {
        private final Path newDir;
        private final Path oldDir;
        private final Set<String> result;
        private final boolean useRelativePath;
        private final Logger logger;

        public CompareFileVisitor(Path newDir, Path oldDir, Set<String> result,boolean useRelativePath,Logger logger) {
            this.newDir = newDir;
            this.oldDir = oldDir;
            this.result = result;
            this.useRelativePath = useRelativePath;
            this.logger = logger;
        }

        @Override
        public FileVisitResult visitFile(Path newPath, BasicFileAttributes attrs) throws IOException {
            if (!newPath.toFile().getName().endsWith(".java")) {
                return FileVisitResult.CONTINUE;
            }
            Path relativePath = newDir.relativize(newPath);
            Path oldPath = oldDir.resolve(relativePath);

            File newFile = newPath.toFile();
            File oldFile = oldPath.toFile();

            if (!oldFile.exists()) {
                String item = useRelativePath ? relativePath.toString() : newFile.getAbsolutePath()
                logger.error("==fastdex found change: " + item)
                item = item.substring(0,item.length() - ".java".length())
                item = "${item}.class"
                result.add(item);
            }
            else if ((newFile.lastModified() != oldFile.lastModified())
                    || (newFile.length() != oldFile.length())) {
                String item = useRelativePath ? relativePath.toString() : newFile.getAbsolutePath()
                logger.error("==fastdex found change: " + item)
                item = item.substring(0,item.length() - ".java".length())
                item = "${item}.class"
                result.add(item);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
