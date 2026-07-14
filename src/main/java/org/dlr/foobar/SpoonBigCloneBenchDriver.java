package org.dlr.foobar;

import fr.inria.controlflow.NaiveExceptionControlFlowStrategy;
import fr.inria.controlflow.ControlFlowBuilder;
import fr.inria.controlflow.ControlFlowGraph;
import fr.inria.controlflow.ControlFlowNode;
import com.ibm.wala.util.graph.dominators.Dominators;
import com.ibm.wala.util.graph.AbstractGraph;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.fsu.bytecode.ByteCodePathExtraction;
import org.fsu.bytecode.HashEncoderRegisterCode;
import org.fsu.codeclones.*;
import spoon.Launcher;
import spoon.reflect.cu.position.NoSourcePosition;
import spoon.reflect.declaration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.HelpFormatter;
import spoon.support.reflect.declaration.CtConstructorImpl;
import spoon.support.reflect.declaration.CtMethodImpl;
import spoon.reflect.declaration.CtClass;
import spoon.processing.AbstractProcessor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.EnumSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: add time to logging ...

public class SpoonBigCloneBenchDriver extends AbstractProcessor<CtClass> {
    static boolean saveOutput = false;

    static FileBasedConfiguration config = null;
    public static int pathExtractionMode = 1;
    public static boolean encodeAsInRegistercode = false;

    static String outputFileName = "/home/hanno/CodeCloner/dominator4java/SPOON/resultFiles/analysize/";

  static Object monitorAddPaths = new Object();
  final static Logger logger =
      LoggerFactory.getLogger(SpoonBigCloneBenchDriver.class);

   
  private final AtomicInteger successAST = new AtomicInteger();
  private final AtomicInteger successCFG = new AtomicInteger();
  private final AtomicInteger successDom = new AtomicInteger();
  private final AtomicInteger totalMethods = new AtomicInteger();
  private final AtomicInteger totalFiles = new AtomicInteger();
  private final AtomicInteger successPath = new AtomicInteger();
  private final Queue<AnalysisFailure> analysisFailures = new ConcurrentLinkedQueue<>();
  private final Queue<String> cloneOutput = new ConcurrentLinkedQueue<>();
  private boolean errors, output, skipclones, exceptions;
  public static boolean bytecode=false;
    
  //private Path currentFile;
  private static final ThreadLocal<Path> currentFile = new ThreadLocal<>();
  private String workingDirectory, outputDirectory;
  private List<Path> sourceRoots;
  private String[] sourceClasspath = new String[0];

  private static final ArrayList<MethodTuple> outputTuples = new ArrayList<MethodTuple>();
  private MethodTuple[] outputTuplesArray;

  public SpoonBigCloneBenchDriver(String workingDirectory) {
    this.workingDirectory = workingDirectory;
    this.sourceRoots = List.of(Paths.get(workingDirectory));
  }

  void setErrors(boolean errors) {this.errors = errors;}
  void setOutput(boolean output) {this.output = output;}
  void setOutputDir(String outputDirectory) {this.outputDirectory = outputDirectory;}

  public static void main(String[] args) {
    // defining the command line parser using Apache Commons CLI
    // cf. https://commons.apache.org/proper/commons-cli
    Options options = new Options();

    // flag for exception mapping strategy
    Option optionX = new Option("x", "exceptions", false,
        "Enable Spoon's naive exception control flow strategy");
    options.addOption(optionX);
    // source directory option
    Option option = new Option("d", "directory",
        true, "working directory for bigclonebench");
    option.setRequired(true);
    options.addOption(option);
    options.addOption(Option.builder()
        .longOpt("source-root")
        .hasArg()
        .argName("directory")
        .desc("Source root to scan; repeat for one source set (defaults to --directory)")
        .get());
    options.addOption(Option.builder()
        .longOpt("classpath-file")
        .hasArg()
        .argName("file")
        .desc("UTF-8 file containing one compiled classpath entry per line")
        .get());
    options.addOption(new Option("e", "error-file", true,
        "Write errors to file"));
    options.addOption(new Option("s", "skipclones", false,
        "Skip clone detection, just generate encoding"));
    // output directory option
    options.addOption(new Option("o", "out",
            true, "basedir for output of ControlFlowGraph, DominatorTree"));

    // help option
    options.addOption(new Option("h", "help", false,
        "Print help"));
    HelpFormatter formatter = HelpFormatter.builder().setShowSince(false).get();
    String command = "./gradlew --args=\"--directory=dataset [--out=out_basedir]\"";


    try {
      // parsing command line arguments
      CommandLineParser parser = new DefaultParser();
      CommandLine cmd = parser.parse(options, args);
      if (cmd.hasOption("help")) {
        printHelp(formatter, command, options);
        System.exit(0);
      }
      Path workingDirectoryPath = validatedWorkingDirectory(cmd.getOptionValue("directory"));
      String workingDirectory = workingDirectoryPath.toString();
      // TODO
      int folder = 13;
      //String workingDirectory = "/home/hanno/CodeCloner/BigCloneEval/ijadataset/bcb_reduced/" + folder;
      logger.info("Traversing working directory {} ...", workingDirectory);
      long start=System.nanoTime();
      SpoonBigCloneBenchDriver driver = new SpoonBigCloneBenchDriver(workingDirectory);
      driver.setSourceRoots(validatedSourceRoots(cmd, workingDirectoryPath));
      driver.setSourceClasspath(validatedSourceClasspath(cmd));
      driver.skipclones = cmd.hasOption("skipclones");
      driver.setErrors(cmd.hasOption("error-file"));
      driver.setOutput(cmd.hasOption("out"));
      driver.exceptions = cmd.hasOption("exceptions");
      if (cmd.hasOption("out"))
        driver.setOutputDir(cmd.getOptionValue("out"));

        FileBasedConfiguration configuration = null;
        String configFileName="config/default.properties";
        Parameters params = new Parameters();
        FileBasedConfigurationBuilder<FileBasedConfiguration> builder =
                new FileBasedConfigurationBuilder<FileBasedConfiguration>(PropertiesConfiguration.class)
                        .configure(params.properties()
                                .setFileName(configFileName));
        try {
            configuration = builder.getConfiguration();
        } catch (ConfigurationException e) {
            e.printStackTrace();
        }
        try {
            Environment.THREADSIZE = configuration.getInt("THREADSIZE");
            Environment.METRIC = configuration.getString("METRIC").equals("NW") ? MetricKind.NW : configuration.getString("METRIC").equals("LEVENSHTEIN") ? MetricKind.LEVENSHTEIN : MetricKind.LCS;
            Environment.PATHSINSETS = configuration.getBoolean("SPLITTING") ? EncoderKind.SPLITTING : EncoderKind.UNSPLITTING;
            Environment.TECHNIQUE = configuration.getBoolean("USEHASH") ? EncoderKind.HASH : EncoderKind.COMPLETEPATH;
            Environment.MD5 = configuration.getBoolean("USEMD5");
            Environment.THRESHOLD = configuration.getFloat("THRESHOLD");
            Environment.MINSIZE = configuration.getInt("MINFUNCTIONSIZE");
            Environment.SUPPORTCALLNAMES = configuration.getBoolean("USEFUNCTIONNAMES");
            Environment.WIDTHUPPERFAKTOR = configuration.getFloat("UPPERFACTOR");
            Environment.MINNODESNO = configuration.getBoolean("SPLITTING") ? 1 : 3;
            Environment.OUTPUT = configuration.getBoolean("OUTPUT");
            Environment.BYTECODEBASED= configuration.getBoolean("BYTECODEBASEDCLONEDETECTION");
            Environment.USEREGISTERCODE= configuration.getBoolean("REGISTERCODE_STACKCODE");
            Environment.STUBBERPROCESSING=configuration.getBoolean("STUBBERPROCESSING");
            if ( Environment.BYTECODEBASED && Environment.USEREGISTERCODE)
            {
                Environment.BREMOVESMALLPATHES =0.4f;
                Environment.BPATHESDIFF =0.3f;
                Environment.WIDTHLOWERNO=5;
                //Environment.WIDTHUPPERFAKTOR=1.5F;
                Environment.MINNODESNO=3;
                Environment.MAXDIFFNODESNO=7;
            }
            else {
                Environment.BREMOVESMALLPATHES =0.3f;
                Environment.BPATHESDIFF =0.3f;
                //Environment.THRESHOLD=0.15F;
                Environment.WIDTHLOWERNO=3;
                //Environment.WIDTHUPPERFAKTOR=1.3F;


            }

        }catch (NoSuchElementException ex)
        {
            ex.printStackTrace();
        }

      int poolSize=Environment.THREADSIZE;

      if (Environment.BYTECODEBASED) {
          outputFileName += "resultBytecode_" + folder;

        configFileName=Environment.USEREGISTERCODE? "config/Patterns/ConfigRegisterCodePatterns":"config/Patterns/ConfigByteCodePatterns";
        params = new Parameters();
        builder =
                new FileBasedConfigurationBuilder<FileBasedConfiguration>(PropertiesConfiguration.class)
                        .configure(params.properties()
                                .setFileName(configFileName));
        try {
          config = builder.getConfiguration();
        } catch (ConfigurationException e) {
          e.printStackTrace();
        }

        SpoonBigCloneBenchDriver.bytecode=true;
        SimpleDateFormat f= new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");
        Date startB = new Date(System.currentTimeMillis());
        SpoonBigCloneBenchDriver.outputTuples.addAll(ByteCodePathExtraction.extractPathes(workingDirectory,Environment.MINSIZE,Environment.PATHSINSETS,Environment.TECHNIQUE,config));
        Date endB = new Date(System.currentTimeMillis());
        logger.info(f.format(startB));
        logger.info(f.format(endB));
      }
      else {
          outputFileName += "resultSourcecode_" + folder;

          // init config
          configFileName = "config/Patterns/ConfigSourceCodePatterns";
          params = new Parameters();
          builder =
                  new FileBasedConfigurationBuilder<FileBasedConfiguration>(PropertiesConfiguration.class)
                          .configure(params.properties()
                                  .setFileName(configFileName));
          try {
              config = builder.getConfiguration();
          } catch (ConfigurationException e) {
              e.printStackTrace();
          }

          // init pathExtractionMode
          // TODO
          if (config != null){
              int pathExtractionModeConfig = config.getInt("pathExtractionMode");
              if (0 < pathExtractionModeConfig && pathExtractionModeConfig < 4){
                  pathExtractionMode = pathExtractionModeConfig;
              }
              else {
                  try {
                      throw new Exception("pathExtractionMode has Unknown Value: " + pathExtractionModeConfig);
                  }
                  catch (Exception ignored) {}
              }
              if (config.getBoolean("encodeAsInRegistercode")){
                  encodeAsInRegistercode = true;
              }
          }

            // traversing the benchmark directory and calling the Spoon driver

            ForkJoinPool myPool = new ForkJoinPool(poolSize);
            try {
              myPool.submit(driver::processSourceFiles).get();
            } finally {
              myPool.shutdown();
            }

      // logging
      logger.info("Successfully created AST for {} out of {} files",
          driver.successAST.get(), driver.totalFiles.get());
      logger.info("Successfully created CFG for {} out of {} methods",
          driver.successCFG.get(), driver.totalMethods.get());
      logger.info("Successfully created DomTree for {} out of {} methods",
          driver.successDom.get(), driver.totalMethods.get());
      logger.info("Successfully encoded paths for {} out of {} methods",
          driver.successPath.get(), driver.totalMethods.get());
      }
      long end1=System.nanoTime();

      if (driver.errors) {
        driver.logErrors(cmd.getOptionValue("error-file"));
      }
      int analysisExitCode = driver.analysisExitCode();
      if (analysisExitCode != 0) {
        logger.error("Analysis incomplete: {} source or method failures", driver.analysisFailures.size());
        System.exit(analysisExitCode);
      }

      if (!driver.skipclones) {
        driver.outputTuplesArray= SpoonBigCloneBenchDriver.outputTuples.toArray(new MethodTuple[SpoonBigCloneBenchDriver.outputTuples.size()]);

        ThreadPoolExecutor executor =(ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);
        List<Future<?>> cloneTasks = new ArrayList<>();
        try {
          for (int i = 0; i < poolSize; i++) {
            int taskStart = i;
            cloneTasks.add(executor.submit(() -> driver.detectClones(taskStart, poolSize)));
          }
          for (Future<?> cloneTask : cloneTasks) {
            cloneTask.get();
          }
        } finally {
          executor.shutdownNow();
        }
        try {
          driver.writeCloneOutput();
        } catch (IOException e) {
          logger.error("Could not write clone output", e);
          System.exit(1);
        }
      }
      long end2=System.nanoTime();
      logger.info("Time create pathes= "+TimeUnit.MILLISECONDS.convert(end1-start, TimeUnit.NANOSECONDS));
      logger.info("Time find clones= "+TimeUnit.MILLISECONDS.convert(end2-end1, TimeUnit.NANOSECONDS));

      logger.info("--- Numbers of Clones: " + driver.cloneOutput.size());
    } catch (ParseException e) {
      System.out.println(e.getMessage());
      printHelp(formatter, command, options);
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Analysis interrupted", e);
      System.exit(1);
    } catch (ExecutionException e) {
      logger.error("Analysis failed", e.getCause());
      System.exit(1);
    }
  }


  // Preparation for clone detection ->changes must be compiles with ./gradlew jar in SPOON
  void detectClones(int start, int add) {
      Encoder myEncoder;
      if (!bytecode) {
          if (Environment.TECHNIQUE == EncoderKind.COMPLETEPATH)
              myEncoder = new CompletePathEncoder();
          else if (Environment.TECHNIQUE == EncoderKind.HASH)
              myEncoder = new HashEncoder();
          else if (Environment.TECHNIQUE == EncoderKind.MULTISET)
              myEncoder = new SortedMultisetPathEncoder();
          else
              myEncoder = new AbstractEncoder();
      }
      else {
          if (Environment.TECHNIQUE == EncoderKind.COMPLETEPATH)
              myEncoder = new CompletePathEncoder();
          else if (Environment.TECHNIQUE == EncoderKind.HASH)
              myEncoder = new HashEncoderRegisterCode();
          else if (Environment.TECHNIQUE == EncoderKind.MULTISET)
              myEncoder = new SortedMultisetPathEncoder();
          else
              myEncoder = new AbstractEncoder();
      }

      for (int countIndex1=start;countIndex1<outputTuplesArray.length;countIndex1=countIndex1+add) {
          MethodTuple cf_1=outputTuplesArray[countIndex1];

          for (int coundIndex2=countIndex1+1;coundIndex2<outputTuplesArray.length;coundIndex2++) {
              MethodTuple cf_2=outputTuplesArray[coundIndex2];

              if (!(Environment.BYTECODEBASED && !Environment.STUBBERPROCESSING))
              if((cf_1.info.endLine-cf_1.info.startLine+1)<Environment.MINSIZE ||  (cf_2.info.endLine-cf_2.info.startLine+1)<Environment.MINSIZE)
                  continue;
              // this statement is useless
              // if (cf_1.info.fileName.equals("1359154.java") && cf_2.info.fileName.equals("1359154.java")) { String s=""; }
              if(myEncoder.areTwoDescriptionSetsSimilar(cf_1.encodePathSet,
                                      cf_2.encodePathSet,
                                      Environment.METRIC,
                                      Environment.SORTED, // true=>sorted
                                      Environment.RELATIVE, // true => relativ
                                      Environment.THRESHOLD)) {
                                                          //Levenstein Splitting und Unsplitting  0.35
                                                          //LCS Splitting und Unsplitting 0.3F
                   if(Environment.OUTPUT) {
                        if ((cf_2.info.subDir.equals(cf_1.info.subDir) && cf_1.info.fileName.equals(cf_2.info.fileName)) &&
                                ((cf_1.info.startLine > cf_2.info.startLine && cf_1.info.endLine < cf_2.info.endLine) ||
                                        (cf_2.info.startLine > cf_1.info.startLine && cf_2.info.endLine < cf_1.info.endLine)))
                            continue;
                        StringBuilder builder = new StringBuilder();
                        builder.append(cf_1.info.subDir)
                                .append(",")
                                .append(cf_1.info.fileName);
                       if (!(Environment.BYTECODEBASED && !Environment.STUBBERPROCESSING))
                                builder.append(",")
                                .append(cf_1.info.startLine)
                                .append(",")
                                .append(cf_1.info.endLine);
                       String part1 =builder.toString();
                               builder = new StringBuilder();
                       builder.append(cf_2.info.subDir)
                                .append(",")
                                .append(cf_2.info.fileName);
                       if (!(Environment.BYTECODEBASED && !Environment.STUBBERPROCESSING))
                           builder.append(",")
                                .append(cf_2.info.startLine)
                                .append(",")
                                .append(cf_2.info.endLine);
                       String part2=builder.toString();
                       if (!part1.equals(part2)) {
                           driverCloneOutput(part1, part2);
                       }
                  }
              }
          }
      }
  }

  public void process(CtClass type) {
      String dir = "";
      if (output) {
          // directory that all the graphs of the type are written to
          dir = convertToOutputDirectory(currentFile.get());
          // make director
          new File(dir).mkdirs();
      }
      // TODO
      List<MethodTuple> tmpList= new ArrayList<>();
      for (Object m : type.getTypeMembers()) {
          try {
              List<List<Encoder>> l=extractGraphs(type, m, dir);
              if (l != null){
                  tmpList.add(new MethodTuple((CtExecutable) m,l,SpoonBigCloneBenchDriver.currentFile.get()));
              }
          } catch (Throwable e) {
              reportAnalysisFailure(e);
          }
      }
      if (!skipclones && pathExtractionMode != 1) {
          // get current method output and search for Subfunctions
          ArrayList<MethodTuple> methodOutputs = new ArrayList<>();
          ArrayList<MethodTuple> subFunctions = new ArrayList<>();
          for (MethodTuple currentTuple : tmpList) {
              int startLine = currentTuple.info.startLine;
              int endLine = currentTuple.info.endLine;
              boolean added = false;

              for (int i = 0; i < methodOutputs.size(); i++) {
                  MethodTuple methodTuple = methodOutputs.get(i);
                  int currentStart = methodTuple.info.startLine;
                  int currentEnd = methodTuple.info.endLine;

                  if (startLine > currentStart && endLine < currentEnd) {
                      // first case -> between currentStart and currentEnd
                      // So it is obviously a subfunction
                      subFunctions.add(methodTuple);
                      currentTuple.encodePathSet.addAll(methodTuple.encodePathSet);
                      added = true;
                      break;
                  } else if (startLine < currentStart && endLine > currentEnd) {
                      // second case -> subfunction is already in list
                      ArrayList<MethodTuple> removeLater = new ArrayList<>();
                      // can be over more than one line
                      int c;
                      for (c = i; c < methodOutputs.size(); c++) {
                          currentEnd = methodOutputs.get(c).info.endLine;
                          if (endLine > currentEnd) {
                              removeLater.add(methodOutputs.get(c));
                          } else
                              break;

                      }
                      for (MethodTuple entry : removeLater) {
                          subFunctions.add(entry);
                          currentTuple.encodePathSet.addAll(methodTuple.encodePathSet);
                          methodOutputs.remove(entry);
                      }
                      methodOutputs.add(i, methodTuple);
                      added = true;
                      break;
                  } else if (endLine < currentEnd) {
                      methodOutputs.add(i, methodTuple);
                      added = true;
                      break;
                  }
              }
              if (!added) {
                  methodOutputs.add(currentTuple);
              }
          }
          synchronized (monitorAddPaths){
              outputTuples.addAll(methodOutputs);
              if (pathExtractionMode == 3){
                  outputTuples.addAll(subFunctions);
              }
          }
      }
  }

  public void setSkipClones(boolean skipClones)
  {
    this.skipclones=skipClones;
  }

  void setSourceRoots(List<Path> sourceRoots) {
    this.sourceRoots = List.copyOf(sourceRoots);
  }

  void setSourceClasspath(List<Path> sourceClasspath) {
    this.sourceClasspath = sourceClasspath.stream().map(Path::toString).toArray(String[]::new);
  }

  public List<List<Encoder>> extractGraphs(CtType type, Object m, String dir)
  {
	
      if ((m instanceof CtMethodImpl) && ((CtMethodImpl)m).isAbstract())
	  return null;
	      
      if (!(m instanceof CtMethodImpl) && !(m instanceof CtConstructorImpl)) {
	  return null;
      }
      if (!((CtExecutable) m).getPosition().isValidPosition()) {
	  return null;
      }

      if (!skipclones) {
        MethodInfo info = new MethodInfo((CtExecutable) m);
        if ((info.endLine - info.startLine + 1) < Environment.MINSIZE){
          return null;
        }
        // TODO
        /*if (info.startLine != 100 && info.startLine != 272){
            System.out.println("Sourcecode line chooser activate!");
            return null;
        }*/
      }
    try {
       
      // building the control flow graph using the Spoon library
      // cf. https://spoon.gforge.inria.fr
      String cfg_name = methodID(type, (CtExecutable) m) + "_cfg";
      ControlFlowGraph cfg = makeCFG((CtExecutable) m, cfg_name);
      successCFG.incrementAndGet();

        // write the cfg and the domtree
        if (config.getBoolean("createCFGGraph")){
            String resultDirectory = config.getString("graphResultDirectory");
            System.out.println("Writing CFG Graph to Directory " + resultDirectory + "...");
            writeToPath(resultDirectory + "/" + cfg_name + ".dot", cfg.toGraphVisText());
        }
      // building the dominator tree using the WALA library
      // cf. https://github.com/wala/WALA
      String domtree_name = methodID(type, (CtExecutable) m) + "_domtree";
      DominatorTree domtree = makeDomTree(cfg, domtree_name);

        if (config.getBoolean("createDominatorTreeGraph")){
            String resultDirectory = config.getString("graphResultDirectory");
            System.out.println("Writing Dominator-tree Graph to Directory " + resultDirectory + "...");
            writeToPath(resultDirectory + "/" + domtree_name + ".dot", domtree.toGraphVisText());
        }

      successDom.incrementAndGet();
      String encodePathSet_name = methodID(type, (CtExecutable) m) + "_encodePathSet";

      //System.out.println("Dom Tree created");
      //!!!!!!System.out.println(cfg);!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      List<List<Encoder>> encodePathSet = domtree.encodePathSet(Environment.PATHSINSETS, Environment.TECHNIQUE, Environment.SETORDER);

      successPath.incrementAndGet();

      if (!skipclones && pathExtractionMode == 1) {
        synchronized (monitorAddPaths) {
            this.outputTuples.add(new MethodTuple((CtExecutable) m, encodePathSet,this.currentFile.get()));
        }
      }
      if (output) {
          System.out.println("Writing EncodedPathSet to output directory!");
          writeToPath(dir + encodePathSet_name + ".txt", encodePathSet.toString());
      }
      return encodePathSet;

    } catch (Throwable e) {
      reportAnalysisFailure(e);
    } finally {
      totalMethods.incrementAndGet();
    }
    return null;
  }

  void process(Path inputFile) {
    logger.info("Parsing Java source file {} ...", inputFile);
    currentFile.set(inputFile);

    try {
        // configuring the Spoon library to read from file inputFile
        // cf. https://spoon.gforge.inria.fr/
        Launcher myLauncher = new Launcher();
        myLauncher.getEnvironment().setComplianceLevel(25);
        myLauncher.getEnvironment().setIgnoreSyntaxErrors(false);
        if (sourceClasspath.length == 0 || isModuleDescriptor(inputFile)) {
          myLauncher.getEnvironment().setNoClasspath(true);
        } else {
          myLauncher.getEnvironment().setSourceClasspath(sourceClasspath);
          myLauncher.getEnvironment().setNoClasspath(false);
        }
        myLauncher.addInputResource(inputFile.toString());
        myLauncher.addProcessor(this);
        myLauncher.buildModel();
        if (myLauncher.getEnvironment().getErrorCount() > 0) {
          throw new IllegalStateException(
              "Parser reported " + myLauncher.getEnvironment().getErrorCount() + " syntax errors");
        }
        myLauncher.process();
        successAST.incrementAndGet();
    } catch (Throwable e) {
      reportAnalysisFailure(e);
    } finally {
      totalFiles.incrementAndGet();
      currentFile.remove();
    }
  }

  String methodID(CtType type, CtExecutable m) {
    return type.getSimpleName()
            + "_" + m.getSimpleName()
            + "_" + m.getPosition().getLine()
            + "_" + m.getPosition().getEndLine();
  }

  ControlFlowGraph makeCFG(CtExecutable m, String name) {
    ControlFlowBuilder builder = new ControlFlowBuilder();
    if (this.exceptions || config.getBoolean("finalNodes")) {
        EnumSet<NaiveExceptionControlFlowStrategy.Options> options;
        options = EnumSet.of(NaiveExceptionControlFlowStrategy.Options.ReturnWithoutFinalizers);
        builder.setExceptionControlFlowStrategy(new NaiveExceptionControlFlowStrategy(options, config, exceptions));
    }
    ControlFlowGraph cfg = builder.build(m, config);
    cfg.setName(name);
    cfg.simplify();
    return cfg;
  }
    
  DominatorTree makeDomTree(ControlFlowGraph cfg, String name) {
      AbstractGraph<ControlFlowNode> dom = Dominators.make(cfg, cfg.entry()).dominatorTree();
      DominatorTree domtree = new DominatorTree(dom, config);
      domtree.setName(name);
      return domtree;
  }

  void writeToPath(String path, String written) {
      try {
          FileWriter fw = new FileWriter(path);
          fw.write(written);
          fw.close();
      }
      catch (IOException ignored) {}
  }

  void reportAnalysisFailure(Throwable e) {
    Path source = currentFile.get();
    analysisFailures.add(new AnalysisFailure(
        source == null ? "<unknown>" : source.toString(),
        ExceptionUtils.getStackTrace(e)));
  }

  private void processSourceFiles() {
    SourceFilesByIdentity sourceFilesByIdentity = new SourceFilesByIdentity();
    for (Path sourceRoot : sourceRoots) {
      try (Stream<Path> paths = Files.walk(sourceRoot)) {
        Iterator<Path> sourceFiles = paths.iterator();
        while (sourceFiles.hasNext()) {
          Path sourceFile = sourceFiles.next();
          if (!isJavaSource(sourceFile)) {
            continue;
          }
          try {
            sourceFilesByIdentity.add(sourceFile.toRealPath(), sourceFile);
          } catch (IOException e) {
            throw new UncheckedIOException("Unable to resolve " + sourceFile, e);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to access " + sourceRoot, e);
      }
    }
    sourceFilesByIdentity.sortedSources().parallelStream().forEach(this::process);
  }

  private static final class SourceFilesByIdentity {
    private final Map<Object, List<SourceFile>> sourcesByFileKey = new HashMap<>();
    private final Map<SourceFileFallbackKey, List<SourceFile>> sourcesWithoutFileKey =
        new HashMap<>();

    void add(Path realSource, Path selectedSource) throws IOException {
      BasicFileAttributes attributes =
          Files.readAttributes(realSource, BasicFileAttributes.class);
      Object fileKey = attributes.fileKey();
      List<SourceFile> candidates = fileKey != null
          ? sourcesByFileKey.computeIfAbsent(fileKey, ignored -> new ArrayList<>())
          : sourcesWithoutFileKey.computeIfAbsent(
              new SourceFileFallbackKey(attributes.size(), attributes.lastModifiedTime()),
              ignored -> new ArrayList<>());
      for (int index = 0; index < candidates.size(); index++) {
        SourceFile candidate = candidates.get(index);
        if (Files.isSameFile(candidate.realSource(), realSource)) {
          if (selectedSource.compareTo(candidate.selectedSource()) < 0) {
            candidates.set(index, new SourceFile(realSource, selectedSource));
          }
          return;
        }
      }
      candidates.add(new SourceFile(realSource, selectedSource));
    }

    List<Path> sortedSources() {
      return Stream.concat(
              sourcesByFileKey.values().stream(),
              sourcesWithoutFileKey.values().stream())
          .flatMap(Collection::stream)
          .map(SourceFile::selectedSource)
          .sorted()
          .toList();
    }
  }

  private record SourceFile(Path realSource, Path selectedSource) {}

  private record SourceFileFallbackKey(long size, FileTime lastModifiedTime) {}

  private static boolean isJavaSource(Path path) {
    return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
  }

  private static boolean isModuleDescriptor(Path path) {
    return path.getFileName().toString().equals("module-info.java");
  }

  private static Path validatedWorkingDirectory(String value) throws ParseException {
    Path directory = Paths.get(value).normalize();
    if (!Files.isDirectory(directory) || !Files.isReadable(directory)) {
      throw new ParseException("Working directory is not a readable directory: " + value);
    }
    return directory;
  }

  private static List<Path> validatedSourceRoots(CommandLine command, Path workingDirectory)
      throws ParseException {
    String[] values = command.getOptionValues("source-root");
    if (values == null || values.length == 0) {
      return List.of(workingDirectory);
    }

    LinkedHashSet<Path> roots = new LinkedHashSet<>();
    Path realWorkingDirectory;
    try {
      realWorkingDirectory = workingDirectory.toRealPath();
    } catch (IOException e) {
      throw new ParseException("Could not resolve working directory: " + workingDirectory);
    }
    for (String value : values) {
      Path configured = Paths.get(value);
      Path configuredSourceRoot = configured.isAbsolute()
          ? configured.normalize()
          : workingDirectory.resolve(configured).normalize();
      if (!Files.isDirectory(configuredSourceRoot) || !Files.isReadable(configuredSourceRoot)) {
        throw new ParseException("Source root is not a readable directory: " + value);
      }
      try {
        Path realSourceRoot = configuredSourceRoot.toRealPath();
        if (!realSourceRoot.startsWith(realWorkingDirectory)) {
          throw new ParseException("Source root resolves outside the working directory: " + value);
        }
        roots.add(workingDirectory.resolve(realWorkingDirectory.relativize(realSourceRoot)).normalize());
      } catch (IOException e) {
        throw new ParseException("Could not resolve source root: " + value);
      }
    }
    return List.copyOf(roots);
  }

  private static List<Path> validatedSourceClasspath(CommandLine command) throws ParseException {
    String value = command.getOptionValue("classpath-file");
    if (value == null) {
      return List.of();
    }

    Path classpathFile = Paths.get(value).toAbsolutePath().normalize();
    if (!Files.isRegularFile(classpathFile) || !Files.isReadable(classpathFile)) {
      throw new ParseException("Classpath file is not a readable file: " + value);
    }

    LinkedHashSet<Path> entries = new LinkedHashSet<>();
    try {
      Path baseDirectory = classpathFile.getParent();
      for (String line : Files.readAllLines(classpathFile, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        Path configured = Paths.get(line);
        Path entry = configured.isAbsolute()
            ? configured.normalize()
            : baseDirectory.resolve(configured).normalize();
        if (!Files.isDirectory(entry) && !Files.isRegularFile(entry)) {
          throw new ParseException("Classpath entry does not exist: " + line);
        }
        if (!Files.isReadable(entry)) {
          throw new ParseException("Classpath entry is not readable: " + line);
        }
        entries.add(entry.toAbsolutePath());
      }
    } catch (IOException e) {
      throw new ParseException("Could not read classpath file: " + value);
    }
    if (entries.isEmpty()) {
      throw new ParseException("Classpath file contains no entries: " + value);
    }
    return List.copyOf(entries);
  }

  private void driverCloneOutput(String first, String second) {
    if (first.compareTo(second) <= 0) {
      cloneOutput.add(first + "," + second);
    } else {
      cloneOutput.add(second + "," + first);
    }
  }

  private static void printHelp(HelpFormatter formatter, String command, Options options) {
    try {
      formatter.printHelp(command, null, options, null, false);
    } catch (IOException e) {
      logger.error("Could not print command help", e);
    }
  }

  private void writeCloneOutput() throws IOException {
    List<String> orderedOutput = cloneOutput.stream().sorted().collect(Collectors.toList());
    for (String clone : orderedOutput) {
      System.out.println(clone);
    }
    if (saveOutput) {
      logger.info("Write Result to File {} ...", outputFileName);
      Files.write(Paths.get(outputFileName), orderedOutput, StandardCharsets.UTF_8);
    }
  }

  int analysisExitCode() {
    return analysisFailures.isEmpty() ? 0 : 2;
  }

  void logErrors(String errorFile) {
    try {
      Path file = Paths.get(errorFile);
      String failures = analysisFailures.stream()
          .sorted(Comparator.comparing(AnalysisFailure::source)
              .thenComparing(AnalysisFailure::stackTrace))
          .map(failure -> failure.source() + ":\n" + failure.stackTrace() + "\n")
          .collect(Collectors.joining());
      Files.writeString(file, failures, StandardCharsets.UTF_8);
    } catch (IOException e) {
      logger.warn("Could not write error file {}", errorFile);
    }
  }

  private record AnalysisFailure(String source, String stackTrace) {}

  String convertToOutputDirectory(Path source) {
    // converts the dataset source path into a directory within the output basedir
    // strip workingDir (leading) and .java (tail)
    String temp = source.toString()
            .substring(workingDirectory.length(), source.toString().length() - ".java".length());
    // add outputDir to the front
    return outputDirectory
            + (outputDirectory.endsWith(File.separator) ? "" : File.separator)
            + temp
            + File.separator;
  }
}
