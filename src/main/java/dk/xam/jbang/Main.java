package dk.xam.jbang;

import static java.lang.System.err;
import static java.lang.System.out;
import static picocli.CommandLine.*;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.Variant;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

@Command(name = "jbang", footer = "\nCopyright: 2020 Max Rydahl Andersen, License: MIT\nWebsite: https://github.com/maxandersen/jbang", mixinStandardHelpOptions = false, versionProvider = VersionProvider.class, description = "Compiles and runs .java/.jsh scripts.")
public class Main implements Callable<Integer> {

	@Spec
	CommandSpec spec;

	@Option(names = {"-d",
			"--debug"}, fallbackValue = "4004", parameterConsumer = IntFallbackConsumer.class, description = "Launch with java debug enabled on specified port(default: ${FALLBACK-VALUE}) ")
	int debugPort = -1;

	boolean debug() {
		return debugPort >= 0;
	}

	@Option(names = {"-h", "--help"}, usageHelp = true, description = "Display help/info")
	boolean helpRequested;

	@Option(names = {"--version"}, versionHelp = true, arity = "0", description = "Display version info")
	boolean versionRequested;

	@Parameters(index = "0", description = "A file with java code or if named .jsh will be run with jshell")
	String scriptOrFile;

	@Parameters(index = "1..*", arity = "0..*", description = "Parameters to pass on to the script")
	List<String> params = new ArrayList<String>();

	@Option(names = {"--init"}, description = "Init script with a java class useful for scripting.")
	boolean initScript;

	void info(String msg) {
		spec.commandLine().getErr().println(msg);
	}

	void warn(String msg) {
		info("[jbang] [WARNING] " + msg);
	}

	void quit(int status) {
		out.println(status == 0 ? "true" : "false");
		throw new ExitException(status);
	}

	static String USAGE = "j'bang - Enhanced scripting support for Java on *nix-based systems.\n" + "\n"
			+ "			Usage:\n" + "			 jbang <script> [<script_args>]...\n" + "\n"
			+ "			The <script> is a java file (.java) with a main method. \n" + "\n"
			+ "			Copyright : 2019 Max Rydahl Andersen\n" + "			License   : MIT\n"
			+ "			Website   : https://github.com/maxandersen/jbang\n" + "".trim();
	private static File prepareScript;

	public static void main(String... args) throws FileNotFoundException {
		int exitcode = getCommandLine().execute(args);
		System.exit(exitcode);
	}

	static CommandLine getCommandLine() {
		return new CommandLine(new Main()).setExitCodeExceptionMapper(new VersionProvider()).setStopAtPositional(true)
				.setOut(new PrintWriter(err, true)).setErr(new PrintWriter(err, true));
	}

	@Override
	public Integer call() throws IOException {

		if (helpRequested) {
			spec.commandLine().usage(err);
			quit(0);
		} else if (versionRequested) {
			spec.commandLine().printVersionHelp(err);
			quit(0);
		}

		if (initScript) {
			var f = new File(scriptOrFile);
			if (f.exists()) {
				warn("File " + f + " already exists. Will not initialize.");
			} else {
				// Use try-with-resource to get auto-closeable writer instance
				try (BufferedWriter writer = Files.newBufferedWriter(f.toPath())) {
					String result = renderInitClass(f);
					writer.write(result);
				}
			}

		} else {
			String cmdline = generateCommandLine();

			out.println(cmdline);
		}
		return 0;
	}

	static String initTemplate = "//usr/bin/env jbang \"$0\" \"$@\" ; exit $?\n"
			+ "// //DEPS <dependency1> <dependency2>\n" + "\n" + "import static java.lang.System.*;\n" + "\n"
			+ "public class {className} {\n" + "\n" + "    public static void main(String... args) {\n"
			+ "        out.println(\"Hello World\");\n" + "    }\n" + "}";

	String renderInitClass(File f) {
		Engine engine = Engine.builder().addDefaults().addLocator(this::locate).build();
		String template = "class {className} public static void main(String... args) {\n}\n }";
		Template helloTemplate = engine.getTemplate("initClass.qt");
		String result = helloTemplate.data("className", getBaseName(f.getName())).render();
		return result;
	}

	/**
	 * @param path
	 * @return the optional reader
	 */
	private Optional<TemplateLocator.TemplateLocation> locate(String path) {
		URL resource = null;
		String basePath = "";
		String templatePath = basePath + path;
		// LOGGER.debugf("Locate template for %s", templatePath);
		resource = locatePath(templatePath);

		if (resource != null) {
			return Optional.of(new ResourceTemplateLocation(resource));
		}
		return Optional.empty();
	}

	private URL locatePath(String path) {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl == null) {
			cl = Main.class.getClassLoader();
		}
		return cl.getResource(path);
	}

	static class ResourceTemplateLocation implements TemplateLocator.TemplateLocation {

		private final URL resource;
		private Optional<Variant> variant = null;

		public ResourceTemplateLocation(URL resource) {
			this.resource = resource;
			this.variant = null;
		}

		@Override
		public Reader read() {
			try {
				return new InputStreamReader(resource.openStream(), Charset.forName("utf-8"));
			} catch (IOException e) {
				return null;
			}
		}

		@Override
		public Optional<Variant> getVariant() {
			return variant;
		}

	}

	public static String getBaseName(String fileName) {
		int index = fileName.lastIndexOf('.');
		if (index == -1) {
			return fileName;
		} else {
			return fileName.substring(0, index);
		}
	}

	String generateCommandLine() throws FileNotFoundException {
		prepareScript = prepareScript(scriptOrFile);

		Scanner sc = new Scanner(prepareScript);
		sc.useDelimiter("\\Z");

		Script script = new Script(sc.next());

		List<String> dependencies = script.collectDependencies();

		var classpath = new DependencyUtil().resolveDependencies(dependencies, Collections.emptyList(), true);
		List<String> optionalArgs = new ArrayList<String>();

		var javacmd = "java";
		if (prepareScript.getName().endsWith(".jsh")) {
			javacmd = "jshell";
			if (!classpath.isBlank()) {
				optionalArgs.add("--class-path " + classpath);
			}
			if (debug()) {
				info("debug not possible when running via jshell.");
			}

		} else {
			optionalArgs.add("--source 11");
			if (debug()) {
				optionalArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + debugPort);
			}
			if (!classpath.isBlank()) {
				optionalArgs.add("-classpath " + classpath);
			}
		}

		List<String> fullArgs = new ArrayList<>();
		fullArgs.add(javacmd);
		fullArgs.addAll(optionalArgs);
		fullArgs.add(scriptOrFile);
		fullArgs.addAll(params);

		return fullArgs.stream().collect(Collectors.joining(" "));
	}

	/**
	 * return the list arguments that relate to jbang. First arguments that starts
	 * with '-' and is not just '-' for stdin goes to jbang, rest goes to the
	 * script.
	 *
	 * @param args
	 * @return the list arguments that relate to jbang
	 */
	static String[] argsForJbang(String... args) {
		final List<String> argsForScript = new ArrayList<>();
		final List<String> argsForJbang = new ArrayList<>();

		/*
		 * if (args.length > 0 && "--init".equals(args[0])) {
		 * argsForJbang.addAll(Arrays.asList(args)); return; }
		 */

		boolean found = false;
		for (var a : args) {
			if (!found && a.startsWith("-") && a.length() > 1) {
				argsForJbang.add(a);
			} else {
				found = true;
				argsForScript.add(a);
			}
		}

		if (!argsForScript.isEmpty() && !argsForJbang.isEmpty()) {

			argsForJbang.add("--");
		}

		argsForJbang.addAll(argsForScript);
		return argsForJbang.toArray(new String[argsForJbang.size()]);

	}

	private static File prepareScript(String scriptresouce) {
		File scriptFile = null;

		// we need to keep track of the scripts dir or the working dir in case of stdin
		// script to correctly resolve includes
		// var includeContext = new File(".").toURI();

		// map script argument to script file
		var probe = new File(scriptresouce);

		if (!probe.canRead()) {
			// not a file so let's keep the script-file undefined here
			scriptFile = null;
		} else if (probe.getName().endsWith(".java") || probe.getName().endsWith(".jsh")) {
			scriptFile = probe;
		} else {
			// if we can "just" read from script resource create tmp file
			// i.e. script input is process substitution file handle
			// not FileInputStream(this).bufferedReader().use{ readText()} does not work nor
			// does this.readText
			// includeContext = this.absoluteFile.parentFile.toURI()
			// createTmpScript(FileInputStream(this).bufferedReader().readText())
		}

		// support stdin
		/*
		 * if(scriptResource=="-"||scriptResource=="/dev/stdin") { val scriptText =
		 * generateSequence() { readLine() }.joinToString("\n").trim() scriptFile =
		 * createTmpScript(scriptText) }
		 */

		// Support URLs as script files
		/*
		 * if(scriptResource.startsWith("http://")||scriptResource.startsWith("https://"
		 * )) { scriptFile = fetchFromURL(scriptResource)
		 * 
		 * includeContext = URI(scriptResource.run { substring(lastIndexOf('/') + 1) })
		 * }
		 * 
		 * // Support for support process substitution and direct script arguments
		 * if(scriptFile==null&&!scriptResource.endsWith(".kts")&&!scriptResource.
		 * endsWith(".kt")) { val scriptText = if (File(scriptResource).canRead()) {
		 * File(scriptResource).readText().trim() } else { // the last resort is to
		 * assume the input to be a java program scriptResource.trim() }
		 * 
		 * scriptFile = createTmpScript(scriptText) }
		 */
		// just proceed if the script file is a regular file at this point
		if (scriptFile == null || !scriptFile.canRead()) {
			throw new IllegalArgumentException("Could not read script argument " + scriptresouce);
		}

		// note script file must be not null at this point

		return scriptFile;
	}

	static class IntFallbackConsumer implements IParameterConsumer {
		@Override
		public void consumeParameters(Stack<String> args, ArgSpec argSpec, CommandSpec commandSpec) {
			String arg = args.pop();
			try {
				int port = Integer.parseInt(arg);
				argSpec.setValue(port);
			} catch (Exception ex) {
				String fallbackValue = (argSpec.isOption()) ? ((OptionSpec) argSpec).fallbackValue() : null;
				try {
					int fallbackPort = Integer.parseInt(fallbackValue);
					argSpec.setValue(fallbackPort);
				} catch (Exception badFallbackValue) {
					throw new InitializationException("FallbackValue for --debug must be an int", badFallbackValue);
				}
				args.push(arg); // put it back
			}
		}
	}
}
