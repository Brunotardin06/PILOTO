package org.apache.tools.ant.taskdefs.optional.dotnet;

import org.apache.tools.ant.*;
import org.apache.tools.ant.types.*;

import java.io.File;
import java.util.*;

public class Ilasm extends DotnetBaseMatchingTask {

    private static final class Cfg {
        String  targetType, extraOptions;
        boolean verbose, listing, debug = true, failOnError = true;
        File    outputFile, resourceFile, keyfile;
        final Collection<FileSet> references = new ArrayList<>();

        String targetParam()  { return "exe".equals(targetType) ? "/exe"
                                : "library".equals(targetType) ? "/dll" : null; }
        String verboseParam() { return verbose ? null : "/quiet"; }
        String listParam()    { return listing ? "/listing" : "/nolisting"; }
        String debugParam()   { return debug ? "/debug" : null; }
        String outParam()     { return arg("/output=",  outputFile); }
        String resParam()     { return arg("/resource=",resourceFile); }
        String keyParam()     { return arg("/keyfile:", keyfile); }
        private static String arg(String p, File f){ return f == null ? null : p + f; }
    }

    private final Cfg cfg = new Cfg();

    public void setTargetType(TargetTypes t)   { cfg.targetType   = t.getValue(); }
    public void setTargetType(String t)        { cfg.targetType   = t.toLowerCase(); }
    public void setVerbose(boolean b)          { cfg.verbose      = b; }
    public void setListing(boolean b)          { cfg.listing      = b; }
    public void setDebug(boolean b)            { cfg.debug        = b; }
    public void setFailOnError(boolean b)      { cfg.failOnError  = b; }
    public void setOutputFile(File f)          { cfg.outputFile   = f; }
    public void setResourceFile(File f)        { cfg.resourceFile = f; }
    public void setKeyfile(File f)             { cfg.keyfile      = f; }
    public void setExtraOptions(String s)      { cfg.extraOptions = s; }
    public void addReference(FileSet fs)       { cfg.references.add(fs); }

    public void execute() {
        validate();
        NetCommand cmd = new NetCommand(this, "ilasm", "ilasm");
        cmd.setFailOnError(cfg.failOnError);

        addArgs(cmd, cfg.debugParam(), cfg.targetParam(), cfg.listParam(),
                     cfg.outParam(), cfg.resParam(), cfg.verboseParam(),
                     cfg.keyParam(), cfg.extraOptions);

        scanAndAddSources(cmd, "**/*.il");
        addArgs(cmd, refsParameter());

        addFilesAndExecute(cmd, false);
    }

    private void validate() {
        if (cfg.outputFile != null && cfg.outputFile.isDirectory())
            throw new BuildException("destFile cannot be a directory");
    }

    private static void addArgs(NetCommand c, String... args) {
        for (String a : args) if (a != null) c.addArgument(a);
    }

    private String refsParameter() {
        if (cfg.references.isEmpty()) return null;
        List<String> files = new ArrayList<>();
        for (FileSet fs : cfg.references)
            Collections.addAll(files,
                    fs.getDirectoryScanner(getProject()).getIncludedFiles());
        return files.isEmpty() ? null : "/reference:" + String.join(",", files);
    }

    public static class TargetTypes extends EnumeratedAttribute {
        public String[] getValues() { return new String[]{"exe","library"}; }
    }
}
