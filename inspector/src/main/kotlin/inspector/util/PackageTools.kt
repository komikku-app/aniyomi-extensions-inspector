package inspector.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import android.content.pm.PackageInfo
import android.os.Bundle
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.ApkParsers
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.w3c.dom.Element
import org.w3c.dom.Node
import xyz.nulldev.androidcompat.pm.InstalledPackage.Companion.toList
import xyz.nulldev.androidcompat.pm.toPackageInfo
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.xml.parsers.DocumentBuilderFactory

object PackageTools {
    private val logger = KotlinLogging.logger {}

    const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    const val LIB_VERSION_MIN = 14
    const val LIB_VERSION_MAX = 16

    /**
     * Convert dex to jar, a wrapper for the dex2jar library
     */
    fun dex2jar(
        dexFile: File,
        jarFile: File,
    ) {
        // adopted from com.googlecode.dex2jar.tools.Dex2jarCmd.doCommandLine
        // source at: https://github.com/DexPatcher/dex2jar/tree/v2.1-20190905-lanchon/dex-tools/src/main/java/com/googlecode/dex2jar/tools/Dex2jarCmd.java

        val jarFilePath = jarFile.toPath()
        val reader = MultiDexFileReader.open(Files.readAllBytes(dexFile.toPath()))
        val handler = BaksmaliBaseDexExceptionHandler()
        Dex2jar
            .from(reader)
            .withExceptionHandler(handler)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(true)
            .optimizeSynchronized(false)
            .printIR(false)
            .noCode(false)
            .skipExceptions(false)
            .dontSanitizeNames(true)
            .to(jarFilePath)

        if (handler.hasException()) {
            val errorFile = jarFilePath.parent.resolve("${dexFile.nameWithoutExtension}-error.txt")
            logger.error {
                """
                Detail Error Information in File $errorFile
                Please report this file to one of following link if possible (any one).
                https://sourceforge.net/p/dex2jar/tickets/
                https://bitbucket.org/pxb1988/dex2jar/issues
                https://github.com/pxb1988/dex2jar/issues
                dex2jar@googlegroups.com
                """.trimIndent()
            }
            handler.dump(errorFile, emptyArray<String>())
        }

        if (jarFile.exists() && jarFile.length() > 0L) {
            runCatching {
                fixJarStackMaps(jarFile)
            }.onFailure { error ->
                logger.warn(error) { "Failed to fix stack maps for generated jar: ${jarFile.absolutePath}" }
            }
        }
    }

    /**
     * dex2jar output often lacks valid StackMapTable attributes, which makes the JVM
     * reject extension classes built with newer Android toolchains (e.g. minSdk 24).
     */
    private fun fixJarStackMaps(jarFile: File) {
        val fixedJar = File(jarFile.parent, "${jarFile.nameWithoutExtension}_fixed.jar")
        try {
            JarFile(jarFile).use { jar ->
                JarOutputStream(FileOutputStream(fixedJar)).use { jos ->
                    jar.entries().asSequence().forEach { entry ->
                        jos.putNextEntry(JarEntry(entry.name))
                        if (!entry.isDirectory) {
                            jar.getInputStream(entry).use { input ->
                                if (entry.name.endsWith(".class")) {
                                    jos.write(fixClassStackMaps(input.readBytes()))
                                } else {
                                    input.copyTo(jos)
                                }
                            }
                        }
                        jos.closeEntry()
                    }
                }
            }
            Files.move(fixedJar.toPath(), jarFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (fixedJar.exists()) {
                fixedJar.delete()
            }
        }
    }

    private fun fixClassStackMaps(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val writer =
            object : ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                override fun getCommonSuperClass(
                    type1: String,
                    type2: String,
                ): String = "java/lang/Object"
            }
        // Use SKIP_FRAMES to avoid mixing existing (possibly invalid) frames with recomputed ones
        reader.accept(writer, ClassReader.SKIP_FRAMES)
        return writer.toByteArray()
    }

    /** A modified version of `xyz.nulldev.androidcompat.pm.InstalledPackage.info` */
    fun getPackageInfo(apkFilePath: String): PackageInfo {
        val apk = File(apkFilePath)
        return ApkParsers.getMetaInfo(apk).toPackageInfo(apk).apply {
            val parsed = ApkFile(apk)
            val dbFactory = DocumentBuilderFactory.newInstance()
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = parsed.manifestXml.byteInputStream().use(dBuilder::parse)

            logger.trace { parsed.manifestXml }

            applicationInfo.metaData =
                Bundle().apply {
                    val appTag = doc.getElementsByTagName("application").item(0)

                    appTag
                        ?.childNodes
                        ?.toList()
                        .orEmpty()
                        .asSequence()
                        .filter { it.nodeType == Node.ELEMENT_NODE }
                        .map { it as Element }
                        .filter { it.tagName == "meta-data" }
                        .forEach {
                            putString(
                                it.attributes.getNamedItem("android:name").nodeValue,
                                it.attributes.getNamedItem("android:value").nodeValue,
                            )
                        }
                }
        }
    }

    /**
     * loads the extension main class called $className from the jar located at $jarPath
     * It may return an instance of AnimeHttpSource or AnimeSourceFactory depending on the extension.
     */
    fun loadExtensionSources(
        jarFile: File,
        className: String,
    ): Any {
        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()))
        val classToLoad = Class.forName(className, false, classLoader)
        return classToLoad.getDeclaredConstructor().newInstance()
    }
}
