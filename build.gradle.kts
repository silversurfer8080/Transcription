import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("org.example.ui.InterviewApp")
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

// Forward stdin so `gradle run` can read the device-index prompt.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Include the generated icon PNG as a classpath resource so InterviewApp can
// load it at runtime with getResourceAsStream("/icon.png").
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}
tasks.named("processResources") {
    dependsOn("generateIcon")
}

// ── ICO helpers (java.base only — no java.awt) ────────────────────────────────

fun shortLE(buf: OutputStream, v: Int) {
    buf.write(v and 0xFF)
    buf.write((v shr 8) and 0xFF)
}

fun intLE(buf: OutputStream, v: Int) {
    buf.write(v and 0xFF)
    buf.write((v shr 8) and 0xFF)
    buf.write((v shr 16) and 0xFF)
    buf.write((v shr 24) and 0xFF)
}

// ── PNG helpers ───────────────────────────────────────────────────────────────

fun intBE(buf: OutputStream, v: Int) {
    buf.write((v shr 24) and 0xFF); buf.write((v shr 16) and 0xFF)
    buf.write((v shr 8) and 0xFF);  buf.write(v and 0xFF)
}

fun pngChunk(buf: OutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    intBE(buf, data.size)
    buf.write(typeBytes)
    buf.write(data)
    val crc = CRC32().also { it.update(typeBytes); it.update(data) }
    intBE(buf, crc.value.toInt())
}

// Encodes RGBA top-down pixel data as a valid PNG file.
fun writePng(file: File, w: Int, h: Int, rgba: ByteArray) {
    val out = ByteArrayOutputStream()
    // PNG signature
    out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    // IHDR
    val ihdr = ByteArrayOutputStream()
    intBE(ihdr, w); intBE(ihdr, h)
    ihdr.write(byteArrayOf(8, 6, 0, 0, 0))  // 8-bit RGBA, no interlace
    pngChunk(out, "IHDR", ihdr.toByteArray())
    // IDAT: filtered rows (filter=None per row), then DEFLATE-compressed
    val raw = ByteArrayOutputStream()
    for (y in 0 until h) {
        raw.write(0)                                  // filter byte = None
        raw.write(rgba, y * w * 4, w * 4)
    }
    val def = Deflater(Deflater.BEST_COMPRESSION).also { it.setInput(raw.toByteArray()); it.finish() }
    val compressed = ByteArrayOutputStream()
    val tmp = ByteArray(4096)
    while (!def.finished()) { val n = def.deflate(tmp); compressed.write(tmp, 0, n) }
    pngChunk(out, "IDAT", compressed.toByteArray())
    pngChunk(out, "IEND", ByteArray(0))
    file.parentFile.mkdirs()
    file.writeBytes(out.toByteArray())
}

// ── generateIcon — produces build/icon.ico without any AWT dependency ─────────
//
// Writes a 32×32 ICO using the BMP (DIB) inside-ICO format.
// The image is a navy-blue square with white "IA" pixel-art letters.

tasks.register("generateIcon") {
    val iconIco = layout.buildDirectory.file("icon.ico").get().asFile
    val iconPng = layout.buildDirectory.file("generated-resources/icon.png").get().asFile
    outputs.files(iconIco, iconPng)

    doLast {
        val w = 32; val h = 32

        // Pixel buffer — BGRA, stored bottom-up as required by BMP
        // Array row 0 = bottom screen row, row (h-1) = top screen row.
        val px = ByteArray(w * h * 4)

        // Fill with navy blue
        val B = 0x7e.toByte(); val G = 0x23.toByte()
        val R = 0x1a.toByte(); val A = 0xFF.toByte()
        for (i in 0 until w * h) { px[i*4]=B; px[i*4+1]=G; px[i*4+2]=R; px[i*4+3]=A }

        // Draw a white pixel at screen coords (sx, sy) where sy=0 is the top.
        fun dot(sx: Int, sy: Int) {
            if (sx !in 0 until w || sy !in 0 until h) return
            val o = ((h - 1 - sy) * w + sx) * 4   // flip y for bottom-up BMP
            px[o]=0xFF.toByte(); px[o+1]=0xFF.toByte(); px[o+2]=0xFF.toByte(); px[o+3]=0xFF.toByte()
        }
        fun hLine(x0: Int, x1: Int, y: Int)         { for (x in x0..x1) dot(x, y) }
        fun vLine(x: Int, y0: Int, y1: Int)          { for (y in y0..y1) dot(x, y) }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0..y1) hLine(x0, x1, y) }

        // "I" — serifs + stem, centred around x=10..12
        hLine(7, 15, 8);  hLine(7, 15, 9)    // top serif
        rect(10, 10, 12, 21)                   // stem
        hLine(7, 15, 22); hLine(7, 15, 23)    // bottom serif

        // "A" — two diagonal legs + crossbar
        for (s in 0..14) {
            val spread = s * 5 / 14
            dot(17 + spread, 9 + s);  dot(18 + spread, 9 + s)   // left leg
            dot(28 - spread, 9 + s);  dot(27 - spread, 9 + s)   // right leg
        }
        hLine(21, 25, 16); hLine(21, 25, 17)  // crossbar

        // Round the corners with 4 px transparent cut-out
        fun transparent(sx: Int, sy: Int) {
            if (sx !in 0 until w || sy !in 0 until h) return
            val o = ((h - 1 - sy) * w + sx) * 4
            px[o]=0; px[o+1]=0; px[o+2]=0; px[o+3]=0
        }
        for (d in 0..3) {
            for (t in 0..(3-d)) {
                transparent(d, t); transparent(31-d, t)
                transparent(d, 31-t); transparent(31-d, 31-t)
            }
        }

        val bmpDataSize = 40 + px.size + h * 4   // header + pixels + AND mask

        val buf = ByteArrayOutputStream()

        // ICONDIR (6 bytes)
        shortLE(buf, 0); shortLE(buf, 1); shortLE(buf, 1)   // reserved, type=icon, count=1

        // ICONDIRENTRY (16 bytes)
        buf.write(w); buf.write(h); buf.write(0); buf.write(0)  // w, h, colorCount, reserved
        shortLE(buf, 1); shortLE(buf, 32)                        // planes, bitCount
        intLE(buf, bmpDataSize)                                   // size of BMP data
        intLE(buf, 22)                                            // offset (6 + 16)

        // BITMAPINFOHEADER (40 bytes)
        intLE(buf, 40)        // biSize
        intLE(buf, w)         // biWidth
        intLE(buf, h * 2)     // biHeight doubled (XOR + AND mask)
        shortLE(buf, 1)       // biPlanes
        shortLE(buf, 32)      // biBitCount
        intLE(buf, 0)         // biCompression = BI_RGB
        intLE(buf, px.size)   // biSizeImage
        intLE(buf, 0); intLE(buf, 0); intLE(buf, 0); intLE(buf, 0)  // DPI + color table

        // XOR pixel data (already bottom-up) + AND mask (all 0 = fully opaque)
        buf.write(px)
        buf.write(ByteArray(h * 4))   // AND mask: 32px / 8 bits = 4 bytes/row × 32 rows

        iconIco.parentFile.mkdirs()
        iconIco.writeBytes(buf.toByteArray())

        // Convert BGRA bottom-up → RGBA top-down for the PNG classpath resource
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = ((h - 1 - y) * w + x) * 4  // flip row (BMP is bottom-up)
                val dst = (y * w + x) * 4
                rgba[dst]   = px[src + 2]  // R  (BGRA → RGBA: swap B and R)
                rgba[dst+1] = px[src + 1]  // G
                rgba[dst+2] = px[src]      // B
                rgba[dst+3] = px[src + 3]  // A
            }
        }
        writePng(iconPng, w, h, rgba)
        println("  Icon → ${iconIco.absolutePath}")
        println("  PNG  → ${iconPng.absolutePath}")
    }
}

// ── packageApp — native Windows executable via jpackage ───────────────────────
//
// Usage:  ./gradlew packageApp
//
// Output: Desktop\Flocareer\InterviewAssistant\InterviewAssistant.exe
//         Desktop\Interview Assistant.lnk   (Desktop shortcut)
//
// jpackage (JDK 14+) creates a self-contained app-image that bundles a private
// JRE — no Java required on the machine running the exe.
// JavaFX JARs are placed in the app/ dir and exposed as a module-path at
// runtime via the $APPDIR jpackage macro.

tasks.register("packageApp") {
    dependsOn("generateIcon", "jar")
    group = "distribution"
    description = "Packages InterviewAssistant.exe into Desktop\\Flocareer"

    doLast {
        // Locate jpackage from the JVM running this Gradle daemon
        val javaHome = File(System.getProperty("java.home")).let {
            // java.home may point to jre/ inside the JDK on older setups
            if (File(it, "bin/jpackage.exe").exists()) it else it.parentFile
        }
        val jpackageExe = File(javaHome, "bin/jpackage.exe")
        check(jpackageExe.exists()) {
            "jpackage not found at ${jpackageExe.absolutePath}.\n" +
            "Make sure Gradle is running with a full JDK 14+ (not just a JRE).\n" +
            "Set JAVA_HOME to your JDK 21 installation and retry."
        }

        // Resolve the real Windows Desktop (handles OneDrive folder redirection,
        // e.g. C:\Users\moret\OneDrive\Área de Trabalho instead of C:\Users\moret\Desktop)
        val psDesktopOut = ByteArrayOutputStream()
        exec {
            executable("powershell.exe")
            args("-NoProfile", "-Command", "[Environment]::GetFolderPath('Desktop')")
            standardOutput = psDesktopOut
        }
        val realDesktop = File(psDesktopOut.toString().trim())

        // Collect all runtime JARs (app + dependencies including JavaFX) into staging dir
        val inputDir = layout.buildDirectory.dir("jpackage-input").get().asFile
        inputDir.deleteRecursively(); inputDir.mkdirs()

        configurations.runtimeClasspath.get().files
            .filter { it.extension == "jar" }
            .forEach { it.copyTo(File(inputDir, it.name), overwrite = true) }

        val appJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        appJar.copyTo(File(inputDir, appJar.name), overwrite = true)

        val icoFile  = layout.buildDirectory.file("icon.ico").get().asFile
        val destDir  = File(realDesktop, "Flocareer")
        val appImage = File(destDir, "InterviewAssistant")
        appImage.deleteRecursively()

        // jpackage puts all input JARs into <appImage>/app/.
        // "\$APPDIR" is a runtime macro resolved to that directory,
        // so "--module-path \$APPDIR" makes JavaFX modules visible at launch.
        exec {
            executable(jpackageExe.absolutePath)
            args(
                "--type",         "app-image",
                "--name",         "InterviewAssistant",
                "--input",        inputDir.absolutePath,
                "--main-jar",     appJar.name,
                "--main-class",   "org.example.ui.InterviewApp",
                "--icon",         icoFile.absolutePath,
                "--java-options", "--module-path \$APPDIR",
                "--java-options", "--add-modules=javafx.controls,javafx.fxml",
                "--java-options", "-Dfile.encoding=UTF-8",
                "--dest",         destDir.absolutePath
            )
        }

        // Create a Desktop shortcut via PowerShell WScript.Shell
        val exePath    = File(appImage, "InterviewAssistant.exe").absolutePath
        val workingDir = appImage.absolutePath
        val lnkPath    = File(realDesktop, "Interview Assistant.lnk").absolutePath

        exec {
            executable("powershell.exe")
            args(
                "-NoProfile", "-Command",
                """|${'$'}sh = New-Object -ComObject WScript.Shell
                   |${'$'}sc = ${'$'}sh.CreateShortcut('$lnkPath')
                   |${'$'}sc.TargetPath       = '$exePath'
                   |${'$'}sc.WorkingDirectory = '$workingDir'
                   |${'$'}sc.IconLocation     = '$exePath,0'
                   |${'$'}sc.Description      = 'Interview Assistant'
                   |${'$'}sc.Save()
                """.trimMargin()
            )
        }

        println("\n✅  Interview Assistant empacotado!")
        println("   Executável : $exePath")
        println("   Atalho     : $lnkPath")
    }
}
