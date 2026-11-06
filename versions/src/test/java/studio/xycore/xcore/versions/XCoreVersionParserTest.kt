package studio.xycore.xcore.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreReleaseType

/**
 * Per-version profile parsing: the document that decides what gets downloaded, so the difference
 * between a correct install and a broken one lives in these fields.
 */
class XCoreVersionParserTest {

    private val parser = XCoreVersionParser()

    private val android = XCoreRuntimeEnvironment.forAndroid("arm64-v8a", apiLevel = 34)

    private val modernProfile = """
        {
          "id": "1.21.4",
          "type": "release",
          "time": "2024-12-03T11:38:24+00:00",
          "releaseTime": "2024-12-03T10:00:00+00:00",
          "minimumLauncherVersion": 21,
          "complianceLevel": 1,
          "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 21},
          "inheritsFrom": "1.21.3",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "17",
          "assetIndex": {
            "id": "17",
            "url": "https://piston-meta.mojang.com/v1/packages/eee/17.json",
            "sha1": "2222222222222222222222222222222222222222",
            "size": 400000,
            "totalSize": 600000000
          },
          "downloads": {
            "client": {
              "url": "https://piston-data.mojang.com/v1/objects/3333333333333333333333333333333333333333/client.jar",
              "sha1": "3333333333333333333333333333333333333333",
              "size": 25000000,
              "path": "versions/1.21.4/1.21.4.jar"
            }
          },
          "libraries": [
            {
              "name": "com.mojang:logging:1.1.1",
              "downloads": {
                "artifact": {
                  "url": "https://libraries.minecraft.net/com/mojang/logging/1.1.1/logging-1.1.1.jar",
                  "sha1": "4444444444444444444444444444444444444444",
                  "size": 15000,
                  "path": "com/mojang/logging/1.1.1/logging-1.1.1.jar"
                }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.4",
              "downloads": {
                "artifact": {
                  "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.4/lwjgl-3.3.4.jar",
                  "sha1": "5555555555555555555555555555555555555555",
                  "size": 1000,
                  "path": "org/lwjgl/lwjgl/3.3.4/lwjgl-3.3.4.jar"
                },
                "classifiers": {
                  "natives-linux": {
                    "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.4/lwjgl-3.3.4-natives-linux.jar",
                    "sha1": "6666666666666666666666666666666666666666",
                    "size": 900,
                    "path": "org/lwjgl/lwjgl/3.3.4/lwjgl-3.3.4-natives-linux.jar"
                  }
                }
              },
              "natives": {"linux": "natives-linux", "windows": "natives-windows"},
              "extract": {"exclude": ["META-INF/"]}
            },
            {
              "name": "com.mojang:patchy:2.2.10",
              "rules": [{"action": "allow", "os": {"name": "osx"}}]
            },
            {
              "name": "ca.weblite:java-objc-bridge:1.1",
              "rules": [
                {"action": "allow", "os": {"name": "osx"}},
                {"action": "disallow", "os": {"name": "osx", "version": "10\\.\\d+"}}
              ]
            },
            {"name": "net.java.dev.jna:jna-platform:5.14.0"},
            {
              "name": "it.unimi.dsi:fastutil:8.5.12",
              "downloads": {
                "artifact": {
                  "url": "http://libraries.minecraft.net/it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar",
                  "sha1": "7777777777777777777777777777777777777777",
                  "size": 100
                }
              }
            }
          ],
          "arguments": {
            "game": [
              "--username",
              {"rules": [{"action": "allow", "features": {"is_demo_user": {"required": true}}}], "value": ["--demo"]},
              "--version",
              "${'$'}{version}"
            ],
            "jvm": [
              {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
              "-Djava.library.path=${'$'}{natives_directory}",
              {"rules": [{"action": "allow", "features": {"has_custom_resolution": {"required": true}}}], "value": ["--width", "${'$'}{width}"]}
            ]
          },
          "logging": {
            "client": {
              "argument": "-Dlog4j.configurationFile=${'$'}{path}",
              "file": {
                "id": "log4j2-1.12.xml",
                "sha1": "8888888888888888888888888888888888888888",
                "size": 400,
                "url": "https://piston-data.mojang.com/v1/objects/8888888888888888888888888888888888888888/log4j2-1.12.xml"
              },
              "type": "log4j2-xml"
            }
          }
        }
    """.trimIndent()

    @Test
    fun readsTheFieldsTheInstallerDependsOn() {
        val profile = parser.parse(modernProfile, sourceUrl = "https://piston-meta.mojang.com/x.json").valueOrNull()
        assertNotNull("modern profile should parse", profile)
        val read = profile!!
        assertEquals("1.21.4", read.id)
        assertEquals(XCoreReleaseType.RELEASE, read.releaseType)
        assertEquals("1.21.3", read.inheritsFrom)
        assertTrue(read.requiresInheritedParent)
        assertEquals("net.minecraft.client.main.Main", read.mainClass)
        assertEquals(21, read.javaMajorVersion)
        assertEquals("java-runtime-gamma", read.javaRuntimeComponent)
        assertEquals(17, read.assetIndex?.id?.toInt())
        assertEquals(600_000_000L, read.assetIndex?.totalSizeBytes)
        assertEquals(25_000_000L, read.clientDownload?.sizeBytes)
        assertEquals("versions/1.21.4/1.21.4.jar", read.clientDownload?.relativePath)
        assertEquals("https://piston-meta.mojang.com/x.json", read.sourceUrl)
        assertEquals("1.12", read.logging?.id?.substringAfter("log4j2-")?.substringBefore(".xml"))
        assertEquals(6, read.libraries.size)
    }

    @Test
    fun keepsNativesClassifiersAndExtractExcludesTogether() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        val lwjgl = profile.libraries.first { it.coordinate.startsWith("org.lwjgl:lwjgl:") }
        assertTrue(lwjgl.declaresNatives)
        assertFalse(lwjgl.needsMavenCentral)
        assertEquals("natives-linux", lwjgl.natives["linux"])
        assertEquals(
            "6666666666666666666666666666666666666666",
            lwjgl.classifiers["natives-linux"]?.sha1?.value,
        )
        assertEquals(listOf("META-INF/"), lwjgl.extractExcludes)
    }

    @Test
    fun anEntryWithNoDownloadsIsFlaggedForMavenResolution() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        val jna = profile.libraries.first { it.coordinate.startsWith("net.java.dev.jna:jna") }
        assertTrue(jna.needsMavenCentral)
        assertNull(jna.artifact)
    }

    @Test
    fun rulesRunInDocumentOrderAndTheLastMatchWins() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        val bridge = profile.libraries.first { it.coordinate.startsWith("ca.weblite") }
        val osx = XCoreRuntimeEnvironment(osName = "osx", osArch = "64-bit", osVersion = "13.4")
        val osxTen = XCoreRuntimeEnvironment(osName = "osx", osArch = "64-bit", osVersion = "10.15")
        assertFalse("the published disallow must survive the earlier allow", XCoreRule.evaluate(bridge.rules, osxTen))
        assertTrue(XCoreRule.evaluate(bridge.rules, osx))
        assertFalse(XCoreRule.evaluate(bridge.rules, android))
    }

    @Test
    fun anOsNameFilterExcludesThisDeviceAndAnEmptyRuleSetAllowsEverything() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        val patchy = profile.libraries.first { it.coordinate.startsWith("com.mojang:patchy") }
        assertFalse(XCoreRule.evaluate(patchy.rules, android))
        assertTrue(XCoreRule.evaluate(emptyList(), android))
    }

    @Test
    fun featureRulesInvertWhenRequiredIsFalse() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        val conditional = profile.gameArguments.filterIsInstance<XCoreConditionalArgument>().first()
        assertEquals(listOf("--demo"), conditional.values)
        assertFalse("this device is not a demo user", conditional.resolveFor(android).isNotEmpty())
        assertTrue(conditional.resolveFor(android.withFeature("is_demo_user")).isNotEmpty())

        val allowUnlessFeature = listOf(
            XCoreRule(action = XCoreRuleAction.ALLOW),
            XCoreRule(
                action = XCoreRuleAction.DISALLOW,
                features = listOf(XCoreFeatureRequirement("has_custom_resolution", required = false)),
            ),
        )
        assertFalse(
            "required:false is satisfied by the feature being absent, so the later disallow fires",
            XCoreRule.evaluate(allowUnlessFeature, android),
        )
        assertTrue(
            "with the feature on, the disallow no longer matches and the earlier allow stands",
            XCoreRule.evaluate(allowUnlessFeature, android.withFeature("has_custom_resolution")),
        )
        assertFalse(
            "a rule set whose conditions none match leaves the entry out",
            XCoreRule.evaluate(
                listOf(XCoreRule(action = XCoreRuleAction.ALLOW, features = listOf(XCoreFeatureRequirement("is_demo_user", required = true)))),
                android,
            ),
        )
    }

    @Test
    fun jvmArgumentsDropOnlyTheClausesThisDeviceDoesNotMeet() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        val resolved = profile.jvmArgumentsFor(android)
        assertFalse(resolved.contains("-XstartOnFirstThread"))
        assertTrue(resolved.contains("-Djava.library.path=\${natives_directory}"))
        assertEquals(
            listOf("-Djava.library.path=\${natives_directory}"),
            resolved.filter { it.startsWith("-Djava.library.path") },
        )
        assertTrue(
            "the resolution clause is published behind a feature this launcher leaves off",
            profile.jvmArgumentsFor(android.withFeature("has_custom_resolution")).contains("--width"),
        )
        assertFalse(profile.jvmArgumentsFor(android).contains("--width"))
    }

    @Test
    fun gameArgumentsKeepTheirLiteralOrder() {
        val profile = parser.parse(modernProfile).valueOrNull()!!
        assertEquals(
            listOf("--username", "--version", "\${version}"),
            profile.gameArgumentsFor(android),
        )
    }

    @Test
    fun legacyProfilesTurnMinecraftArgumentsIntoOneUnconditionalClause() {
        val legacy = """
            {
              "id": "b1.8.1",
              "type": "old_beta",
              "minecraftArguments": "--username ${'$'}{player_name}  --session ${'$'}{auth_session} --gameDir ${'$'}{game_directory}",
              "mainClass": "net.minecraft.client.Minecraft",
              "downloads": {
                "client": {
                  "url": "https://launcher.mojang.com/v1/objects/9999999999999999999999999999999999999999/server.dat",
                  "sha1": "9999999999999999999999999999999999999999",
                  "size": 1200000
                }
              }
            }
        """.trimIndent()
        val profile = parser.parse(legacy).valueOrNull()
        assertNotNull("pre-1.13 profiles are still installable", profile)
        assertEquals(XCoreReleaseType.OLD_RELEASE, profile!!.releaseType)
        assertEquals(XCoreCore.DEFAULT_JAVA_MAJOR, profile.javaMajorVersion)
        assertNull(profile.assetIndex)
        assertEquals(
            listOf("--username", "\${player_name}", "--session", "\${auth_session}", "--gameDir", "\${game_directory}"),
            profile.gameArgumentsFor(android),
        )
        val literal = profile.gameArguments.singleOrNull() as? XCoreLiteralArgument
        assertNotNull("a legacy profile declares no rules, so its clause must be unconditional", literal)
        assertEquals(profile.gameArgumentsFor(android), literal!!.values)
    }

    @Test
    fun aMajorVersionWrittenAsAnArrayIsReadAsItsFirstElement() {
        val shaped = modernProfile.replace("\"majorVersion\": 21", "\"majorVersion\": [17, 21]")
        assertEquals(17, parser.parse(shaped).valueOrNull()!!.javaMajorVersion)
        val absent = modernProfile.replace("\"majorVersion\": 21", "\"majorVersion\": []")
        assertEquals(XCoreCore.DEFAULT_JAVA_MAJOR, parser.parse(absent).valueOrNull()!!.javaMajorVersion)
    }

    @Test
    fun aFieldTheInstallerNeedsIsAFailureNotASilentDefault() {
        assertTrue(parser.parse("""{"type":"release"}""").errorOrNull() is XCoreError.Network)
        val noId = modernProfile.replace("\"id\": \"1.21.4\",", "")
        assertTrue(parser.parse(noId).errorOrNull() is XCoreError.Network)
        val badType = modernProfile.replace("\"type\": \"release\"", "\"type\": \"nightly\"")
        assertTrue(parser.parse(badType).errorOrNull() is XCoreError.Validation)
        val badSha = modernProfile.replace("3333333333333333333333333333333333333333", "zz")
        assertTrue("a digest that is not a digest cannot verify anything", parser.parse(badSha).errorOrNull() is XCoreError.Validation)
        val badRule = modernProfile.replace("\"action\": \"allow\", \"os\": {\"name\": \"osx\"}", "\"action\": \"maybe\", \"os\": {\"name\": \"osx\"}")
        assertTrue(parser.parse(badRule).errorOrNull() is XCoreError.Validation)
    }

    @Test
    fun anUnusableOsVersionPatternIsReportedInsteadOfCompilingIntoAMatchEverythingRegex() {
        val broken = modernProfile.replace("\"os\": {\"name\": \"osx\"}", "\"os\": {\"name\": \"osx\", \"version\": \"[\"}")
        val error = parser.parse(broken).errorOrNull()
        assertTrue(error is XCoreError.Network)
    }

    @Test
    fun mergingKeepsTheParentClasspathFirstAndTheLoaderOverridesLast() {
        val parent = parser.parse(modernProfile.replace("\"inheritsFrom\": \"1.21.3\",", "")).valueOrNull()!!
        val child = parser.parse(
            """
            {
              "id": "fabric-loader-0.16.9-1.21.4",
              "type": "release",
              "inheritsFrom": "1.21.4",
              "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
              "libraries": [
                {"name": "net.fabricmc:fabric-loader:0.16.9"}
              ],
              "arguments": {"game": ["--fabric"], "jvm": []}
            }
            """.trimIndent(),
        ).valueOrNull()!!

        val merged = parser.merge(child, parent).valueOrNull()
        assertNotNull("a loader profile must merge onto its parent", merged)
        val result = merged!!
        assertEquals("fabric-loader-0.16.9-1.21.4", result.id)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", result.mainClass)
        assertEquals("1.21.4", result.inheritsFrom)
        assertEquals(parent.assetIndex?.id, result.assetIndex?.id)
        assertEquals(parent.clientDownload?.url, result.clientDownload?.url)
        assertEquals(21, result.javaMajorVersion)
        assertEquals(
            parent.libraries.map { it.coordinate } + "net.fabricmc:fabric-loader:0.16.9",
            result.libraries.map { it.coordinate },
        )
        assertEquals(
            parent.gameArgumentsFor(android) + "--fabric",
            result.gameArgumentsFor(android),
        )
    }

    @Test
    fun aChildThatDeclaresNoArgumentsInheritsTheParentsAlone() {
        val parent = parser.parse(modernProfile.replace("\"inheritsFrom\": \"1.21.3\",", "")).valueOrNull()!!
        val child = parser.parse(
            """
            {
              "id": "optifine-1.21.4",
              "type": "release",
              "inheritsFrom": "1.21.4",
              "libraries": [{"name": "of:of:1.0"}]
            }
            """.trimIndent(),
        ).valueOrNull()!!
        val merged = parser.merge(child, parent).valueOrNull()!!
        assertEquals(parent.jvmArgumentsFor(android), merged.jvmArgumentsFor(android))
        assertEquals(parent.libraries.size + 1, merged.libraries.size)
    }

    @Test
    fun refusesToMergeAProfileAgainstTheWrongParent() {
        val parent = parser.parse(modernProfile.replace("\"inheritsFrom\": \"1.21.3\",", "")).valueOrNull()!!
        val child = parser.parse(
            """{"id":"x","type":"release","inheritsFrom":"1.20.4","libraries":[]}""",
        ).valueOrNull()!!
        val error = parser.merge(child, parent).errorOrNull()
        assertTrue(error is XCoreError.Configuration)
        assertTrue(error!!.detail.contains("1.20.4"))
    }

    @Test
    fun toVersionRefRefusesAProfileWithNoClientDownload() {
        val child = parser.parse(
            """{"id":"fabric-1.21.4","type":"release","inheritsFrom":"1.21.4","libraries":[]}""",
        ).valueOrNull()!!
        val error = parser.toVersionRef(child).errorOrNull()
        assertTrue("registering this row would claim an install with nothing to download", error is XCoreError.Validation)
        assertTrue(error!!.detail.contains("fabric-1.21.4"))
    }

    @Test
    fun toVersionRefCarriesTheClientDigestAndRefusesAPublishedHttpUrl() {
        val profile = parser.parse(modernProfile.replace("\"inheritsFrom\": \"1.21.3\",", "")).valueOrNull()!!
        val ref = parser.toVersionRef(profile, lastSeenManifestMillis = 7L, installed = true).valueOrNull()!!
        assertEquals("1.21.4", ref.id)
        assertEquals("3333333333333333333333333333333333333333", ref.clientDownloadSha1.value)
        assertEquals("17", ref.assetIndexId)
        assertEquals(7L, ref.lastSeenManifestMillis)
        assertTrue(ref.installed)

        val insecure = parser.parse(
            modernProfile
                .replace("\"inheritsFrom\": \"1.21.3\",", "")
                .replace("https://piston-data.mojang.com", "http://piston-data.mojang.com"),
        ).valueOrNull()!!
        assertTrue(parser.toVersionRef(insecure).errorOrNull() is XCoreError.Validation)
    }

    /** The parser keeps the constant on its companion; this names it once for the tests. */
    private object XCoreCore {
        const val DEFAULT_JAVA_MAJOR = XCoreVersionParser.DEFAULT_JAVA_MAJOR
    }
}
