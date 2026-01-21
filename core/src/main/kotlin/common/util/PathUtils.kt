package cn.luorenmu.common.util

import java.io.File
import java.nio.file.Path

/**
 *
 * @author LoMu
 * Date 2025/11/2 00:17
 */
object PathUtils {
    val currentDirectory: Path by lazy {
        val jarPath = this::class.java.protectionDomain.codeSource.location.toURI()
        val jarFile = File(jarPath)
        if (jarFile.isDirectory) {
            jarFile.toPath()
        } else {
            jarFile.parentFile.toPath()
        }
    }


    /**
     * @param paths 目录分隔或塞一块
     * 只会创建父级目录
     * 传入aa时生产 E:\code\Kotlin Code\LoMu-QQBot\build\classes\kotlin\main\resources\aa <- 不会创建目录
     * 传入aa/bb时生产 E:\code\Kotlin Code\LoMu-QQBot\build\classes\kotlin\main\resources\aa\bb <- 会创建目录 aa
     */


    fun pathResolve(basePath1: Path, vararg paths: String): Path {
        var basePath = basePath1
        for (path in paths) {
            val normalizedPath = path.replace(Regex("[\\\\/]"), "/").removePrefix("/")
            basePath = basePath.resolve(normalizedPath)
        }
        basePath.parent?.let { parent ->
            if (!parent.toFile().exists()) {
                parent.toFile().mkdirs()
            }
        }
        return basePath
    }

    fun resourcesPathResolve(vararg paths: String): Path {
        val basePath = currentDirectory.resolve("resources")
        return pathResolve(basePath, *paths)
    }

    fun dataPathResolve(vararg paths: String): Path {
        val basePath = currentDirectory.resolve("data")
        return pathResolve(basePath, *paths)
    }

}

fun String.toPath(): Path {
    return PathUtils.pathResolve(PathUtils.currentDirectory, this)
}

fun String.toPathString(): String {
    return PathUtils.pathResolve(PathUtils.currentDirectory, this).toString()
}

fun String.toResourcesPath(): Path {
    return PathUtils.resourcesPathResolve(this)
}

