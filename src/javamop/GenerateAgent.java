package javamop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.nio.channels.FileChannel;

import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;

import java.nio.file.attribute.BasicFileAttributes;

import java.util.Arrays;

/**
 * Handles generating a complete Java agent after .mop files have been processed into .rvm files
 * and .rvm files have been processed into .java files. Based on Owolabi's build-agent.sh.
 * @author A. Cody Schuffelen
 */
public class GenerateAgent {
    
    // Step 9: Move all the jars used to a single location
    private static final String jarBase = new File("lib").getAbsolutePath() + File.separator;
    private static final String ajToolsJar = jarBase + "aspectjtools.jar";
    private static final String ajRtJar = jarBase + "aspectjrt.jar";
    private static final String rtJar = jarBase + "rt.jar";
    private static final String weaverJar = "aspectjweaver.jar";
    private static final String ajWeaverJar = jarBase + weaverJar;
    private static final String baseAspectFile = jarBase + "BaseAspect.aj";
    private static final String manifest = "MANIFEST.MF";
    
    /**
     * Generate a JavaMOP agent.
     * @param outputDir The place to put all the intermediate generated files in.
     * @param aspectname Generates {@code aspectname}.jar.
     * @throws IOException If something goes wrong in the many filesystem operations.
     */
    public static void generate(final File outputDir, final String aspectname) throws IOException {
        final String ajOutDir = outputDir.getAbsolutePath();
        
        // Step 10: Compile the generated Java File (allRuntimeMonitor.java)
        final int javacReturn = runCommandDir(outputDir, "javac", "-d", ".",
            "-cp", ajRtJar + ":" + rtJar, aspectname + "RuntimeMonitor.java");
        if(javacReturn != 0) {
            System.err.println("(javac) Failed to compile agent.");
            return;
        }
        
        // Step 11: Compile the generated AJC File (allMonitorAspect.aj)
        final int ajcReturn = runCommandDir(outputDir, "java",
            "-cp", ajToolsJar + ":" + rtJar + ":" + ajRtJar + ":.", "org.aspectj.tools.ajc.Main",
            "-1.6", "-d", ajOutDir, "-outxml", baseAspectFile, aspectname + "MonitorAspect.aj");
        /*
        if(ajcReturn != 0) {
            System.err.println("(ajc) Failed to compile agent.");
            System.exit(ajcReturn);
        }*/
        final File aopAjc = new File(ajOutDir + File.separator + "META-INF" + File.separator +
            "aop-ajc.xml");
        if(aopAjc.exists()) {}
        else {
            System.err.println("(ajc) Failed to produce aop-ajc.xml");
            return;
        }
        // Step 13: Prepare the directory from which the agent will be built
        final File agentDir = Files.createTempDirectory(outputDir.toPath(), "agent-jar").toFile();
        agentDir.deleteOnExit();
        try {
            // Copy in the postprocessed xml file
            final File metaInf = new File(agentDir, "META-INF");
            final boolean mkdirMetaInfReturn = (metaInf.exists() && metaInf.isDirectory()) ||
                metaInf.mkdir();
            if(mkdirMetaInfReturn) {}
            else {
                System.err.println("(mkdir) Failed to create META-INF");
                return;
            }
            copyFile(aopAjc, new File(metaInf, "aop-ajc.xml"));
            
            // directory to hold compiled library files
            // Copy in all the .class files for all the monitor libraries
            new File(outputDir, "mop").renameTo(new File(agentDir, "mop"));
            
            // # Step 14: copy in the correct MANIFEST FILE
            final File jarManifest = new File(metaInf, manifest);
            copyFile(new File(jarBase + manifest), jarManifest);
            
            // # Step 15: Stepmake the java agent jar
            final int jarReturn = runCommandDir(new File("."), "jar", "cmf", jarManifest.toString(), 
                aspectname + ".jar", "-C", agentDir.toString(), ".");
            if(jarReturn != 0) {
                System.err.println("(jar) Failed to produce final jar");
                return;
            }
            
            System.out.println(aspectname + ".jar is generated.");
        } finally {
            deleteDirectory(agentDir.toPath());
        }
    }
    
    /**
     * Run a command in a directory. Passes the output of the run commands through if the program
     * is in verbose mode.
     */
    private static int runCommandDir(final File dir, final String... args) throws IOException {
        try {
            if(MOPProcessor.verbose) { // -v
                System.out.println(dir.toString() + ": " + Arrays.asList(args).toString());
            }
            final ProcessBuilder builder = new ProcessBuilder();
            builder.command(args).directory(dir);
            if(MOPProcessor.verbose) { // -v
                builder.inheritIO();
            }
            final Process proc = builder.start();
            return proc.waitFor();
        } catch(InterruptedException ie) {
            ie.printStackTrace();
            return -1;
        }
    }
    
    /**
     * Copy the contents of one file to another.
     * @param sourceFile The file to take the contents from.
     * @param destFile The file to output to. Created if it does not already exist.
     * @throws IOException If something goes wrong copying the file.
     */
    private static void copyFile(final File sourceFile, final File destFile) throws IOException {
        // http://www.javalobby.org/java/forums/t17036.html
        if(!destFile.exists()) {
            destFile.createNewFile();
        }
        
        FileChannel source = null;
        FileChannel destination = null;
        try {
            source = new FileInputStream(sourceFile).getChannel();
            destination = new FileOutputStream(destFile).getChannel();
            destination.transferFrom(source, 0, source.size());
        }
        finally {
            
            if(source != null) {
                source.close();
            }
            if(destination != null) {
                destination.close();
            }
        }
    }
    
    /**
     * Delete a directory and all its contents. Every file has to be individually deleted, since
     * there is no built-in java function to delete an entire directory and its contents
     * recursively.
     * @param path The path of the directory to delete.
     * @throws IOException If it cannot traverse the directories or the files cannot be deleted.
     */
    public static void deleteDirectory(final Path path) throws IOException {
        // http://stackoverflow.com/a/8685959
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) 
                    throws IOException {
                // try to delete the file anyway, even if its attributes
                // could not be read, since delete-only access is
                // theoretically possible
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) 
                    throws IOException {
                if (exc == null) {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    // directory iteration failed; propagate exception
                    throw exc;
                }
            }
        });
    }
}