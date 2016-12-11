package cz.zipek.sqflint.linter;

import cz.zipek.sqflint.linter.SQFCommand.Type;
import cz.zipek.sqflint.sqf.SQFBlock;
import cz.zipek.sqflint.output.JSONOutput;
import cz.zipek.sqflint.output.OutputFormatter;
import cz.zipek.sqflint.output.TextOutput;
import cz.zipek.sqflint.parser.ParseException;
import cz.zipek.sqflint.parser.SQFParser;
import cz.zipek.sqflint.parser.Token;
import cz.zipek.sqflint.parser.TokenMgrError;
import cz.zipek.sqflint.preprocessor.SQFInclude;
import cz.zipek.sqflint.preprocessor.SQFMacro;
import cz.zipek.sqflint.preprocessor.SQFPreprocessor;
import cz.zipek.sqflint.sqf.operators.ExitWithOperator;
import cz.zipek.sqflint.sqf.operators.GenericOperator;
import cz.zipek.sqflint.sqf.operators.IfOperator;
import cz.zipek.sqflint.sqf.operators.Operator;
import cz.zipek.sqflint.sqf.operators.ParamsOperator;
import cz.zipek.sqflint.sqf.operators.PathLoader;
import cz.zipek.sqflint.sqf.operators.ThenOperator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Jan Zípek <jan at zipek.cz>
 */
public class Linter extends SQFParser {
	public static final int CODE_OK = 0;
	public static final int CODE_ERR = 1;
	
	private boolean stopOnError = false;
	private boolean skipWarnings = false;
	private boolean jsonOutput = false;
	private boolean outputVariables = false;
	private boolean exitCodeEnabled = false;
	private boolean warningAsError = false;
	private boolean checkPaths = false;
	private String rootPath = null;
	
	private final Map<String, SQFCommand> commands = new HashMap<>();
	private final Set<String> ignoredVariables = new HashSet<>();
	
	private final List<SQFParseException> errors = new ArrayList<>();
	private final List<Warning> warnings = new ArrayList<>();
	private final Map<String, SQFVariable> variables = new HashMap<>();
	
	private final List<SQFInclude> includes = new ArrayList<>();
	private final List<SQFMacro> macros = new ArrayList<>();
	
	private final Map<String, Operator> operators = new HashMap<>();
	
	private SQFPreprocessor preprocessor;
	
	public Linter(InputStream stream) {
		super(stream);
		
		ignoredVariables.addAll(Arrays.asList(new String[] { "_this", "_x", "_foreachindex", "_exception" }));
		
		operators.put("params", new ParamsOperator());
		operators.put("execvm", new PathLoader());
		operators.put("preprocessfile", new PathLoader());
		operators.put("preprocessfilelinenumbers", new PathLoader());
		operators.put("loadfile", new PathLoader());
		operators.put("if", new IfOperator());
		operators.put("then", new ThenOperator());
		operators.put("exitwith", new ExitWithOperator());
	}
	
	public int start() throws IOException {
		if (jsonOutput)
			setTabSize(1);
		
		loadCommands();
		
		SQFBlock block = null;
		
		try {
			block = CompilationUnit();
		} catch (ParseException | TokenMgrError  e) {
			if (e instanceof SQFParseException) {
				getErrors().add((SQFParseException)e);
				//getErrors().add(new SQFParseException(e));
			} else if (e instanceof ParseException) {
				getErrors().add(new SQFParseException((ParseException)e));
			} else if (e instanceof TokenMgrError) {
				getErrors().add(new SQFParseException((TokenMgrError)e));
			}
		} finally {
			if (block != null) {
				block.analyze(this, null);
			}
			
			postParse();
			
			OutputFormatter out;
			if (jsonOutput) {
				out = new JSONOutput();
			} else {
				out = new TextOutput();
			}
			
			out.print(this);
		}
		
		// Always return OK if exit code is disabled
		if (!exitCodeEnabled)
			return CODE_OK;
		
		// Return ERR code when any error was encountered
		return (getErrors().size() > 0) ? CODE_ERR : CODE_OK;
	}
	
	/**
	 * Post parse checks, mainly for warnings.
	 * Currently checks if every used local variable is actually defined.
	 */
	protected void postParse() {
		if (skipWarnings)
			return;
		
		variables.entrySet().stream().forEach((entry) -> {
			SQFVariable var = entry.getValue();
			if (var.isLocal() && var.definitions.isEmpty()) {
				if (!preprocessor.getMacros().containsKey(var.name.toLowerCase())) {
					var.usage.stream().forEach((u) -> {
						if (warningAsError) {
							getErrors().add(new SQFParseException(u, "Possibly undefined variable " + u));
						} else {
							getWarnings().add(new Warning(u, "Possibly undefined variable " + u));
						}
					});
				}
			}
		});
	}
	
	/**
	 * Loads variable assigned to specified ident.
	 * If variable isn't registered yet, it will be.
	 * 
	 * @param ident
	 * @return
	 */
	public SQFVariable getVariable(String ident) {
		SQFVariable var;
		if (!variables.containsKey(ident)) {
			var = new SQFVariable(ident);
			variables.put(ident, var);
		} else {
			var = variables.get(ident);
		}
		
		return var;
	}
	
	@Override
	protected void handleName() throws ParseException {
		// Load current token
		Token name = getToken(1);
		
		// Convert to ident (SQF is case insensitivie)
		String ident = name.toString().toLowerCase();
		
		// If name is exisiting command, do some tests
		// Otherwise, if not macro or ignored variable, handle variable
		if (getCommands().containsKey(ident)) {
			SQFCommand cmd = getCommands().get(ident);
			cmd.test(name, this);
		} else if (!preprocessor.getMacros().containsKey(ident)
				&& !ignoredVariables.contains(ident)) {
			SQFVariable var = getVariable(ident);

			var.usage.add(name);

			if (getToken(2).kind == ASSIGN) {
				var.definitions.add(name);

				if (name.specialToken != null) {
					var.comments.add(name.specialToken);
				} else {
					var.comments.add(null);
				}
			}
		}
	}
	
	/**
	 * Tries to recover from error if enabled.
	 * This allows us to catch more errors per file. Adds error to list of encountered problems.
	 * 
	 * @param ex
	 * @param recoveryPoint 
	 * @return recovery point (EOF or recoveryPoint)
	 * @throws cz.zipek.sqflint.parser.ParseException 
	 */
	@Override
	protected int recover(ParseException ex, int recoveryPoint, boolean skip) throws ParseException {
		// Add to list of encountered errors
		if (!(ex instanceof SQFParseException)) {
			getErrors().add(new SQFParseException(ex));
		} else {
			getErrors().add((SQFParseException)ex);
		}
		
		// Don't actually recover if needed
		if (stopOnError) {
			throw ex;
		}
	
		// Skip token with error
		getNextToken();
		
		// Scan until we reach recovery point or EOF
		// We need to start AT recovery point, so only peek, don't consume
		// Only consume when it isn't recovery point
		Token t;
		while(true) {
			t = getToken(1);
			if (t.kind == recoveryPoint || t.kind == EOF) {
				if (skip) getNextToken();
				break;
			}
			getNextToken();
		}
		
		return t.kind;
	}
	
	/**
	 * Loads commands list from resources.
	 * 
	 * @throws IOException 
	 */
	protected void loadCommands() throws IOException {
		// Binary commands
		Pattern bre = Pattern.compile("(?i)b:([a-z0-9,]*) ([a-z0-9_]*) ([a-z0-9,]*)");
		// Unary commands
		Pattern ure = Pattern.compile("(?i)u:([a-z0-9_]*) ([a-z0-9,]*)");
		// Noargs commands
		Pattern nre = Pattern.compile("(?i)n:([a-z0-9_]*)");
		
		// Load commands list from jar file
		InputStream in = getClass().getResourceAsStream("/res/commands.txt"); 
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		
		// Read line by line
		String line;
		while((line = reader.readLine()) != null) {
			String ident = null;
			SQFCommand.Type type = null;
			String[] left = null;
			String[] right = null;
			
			// Try to match one if the command regexp
			Matcher m = bre.matcher(line);
			if (m.find()) {
				ident = m.group(2).toLowerCase();
				type = SQFCommand.Type.Binary;
				
				left = m.group(1).split(",");
				right = m.group(3).split(",");
			}
			
			m = ure.matcher(line);
			if (m.find()) {
				ident = m.group(1).toLowerCase();
				type = SQFCommand.Type.Unary;
				
				right = m.group(2).split(",");
			}
			
			m = nre.matcher(line);
			if (m.find()) {
				ident = m.group(1).toLowerCase();
				type = SQFCommand.Type.Noargs;
			}
			
			if (ident != null) {
				getCommands().put(ident, new SQFCommand(ident, type));
				
				if (!operators.containsKey(ident)) {
					operators.put(ident, new GenericOperator(ident));
				}
				
				Operator op = operators.get(ident);
				if (op instanceof GenericOperator) {					
					GenericOperator genop = (GenericOperator)op;
					for(GenericOperator.Type ttype : convertToTypes(left)) {
						genop.addLeft(ttype);
					}
					for(GenericOperator.Type ttype : convertToTypes(right)) {
						genop.addRight(ttype);
					}
				}
			}
		}
	}
	
	private GenericOperator.Type[] convertToTypes(String[] values) {
		if (values == null) {
			return new GenericOperator.Type[0];
		}
		
		Set<GenericOperator.Type> types = new HashSet<>();
		for(String tname : values) {
			GenericOperator.Type ttype;
			
			try {
				ttype = GenericOperator.Type.valueOf(tname.toUpperCase());
			} catch(IllegalArgumentException e) {
				ttype = GenericOperator.Type.ANY;
			}
			
			types.add(ttype);
		}
		
		return types.toArray(new GenericOperator.Type[0]);
	}
	
	/**
	 * @param stopOnError the stopOnError to set
	 */
	public void setStopOnError(boolean stopOnError) {
		this.stopOnError = stopOnError;
	}

	/**
	 * @param skipWarnings the skipWarnings to set
	 */
	public void setSkipWarnings(boolean skipWarnings) {
		this.skipWarnings = skipWarnings;
	}

	/**
	 * @param jsonOutput the jsonOutput to set
	 */
	public void setJsonOutput(boolean jsonOutput) {
		this.jsonOutput = jsonOutput;
	}

	/**
	 * @return the skipWarnings
	 */
	public boolean isSkipWarnings() {
		return skipWarnings;
	}

	/**
	 * @return the commands
	 */
	public Map<String, SQFCommand> getCommands() {
		return commands;
	}

	/**
	 * @return the ignoredVariables
	 */
	public Set<String> getIgnoredVariables() {
		return ignoredVariables;
	}

	/**
	 * @return the errors
	 */
	public List<SQFParseException> getErrors() {
		return errors;
	}

	/**
	 * @return the variables
	 */
	public Map<String, SQFVariable> getVariables() {
		return variables;
	}

	/**
	 * @return the outputVariables
	 */
	public boolean isOutputVariables() {
		return outputVariables;
	}

	/**
	 * @param outputVariables the outputVariables to set
	 */
	public void setOutputVariables(boolean outputVariables) {
		this.outputVariables = outputVariables;
	}

	/**
	 * @return the warnings
	 */
	public List<Warning> getWarnings() {
		return warnings;
	}

	/**
	 * @return the includes
	 */
	public List<SQFInclude> getIncludes() {
		return includes;
	}

	/**
	 * @return the macros
	 */
	public List<SQFMacro> getMacros() {
		return macros;
	}

	/**
	 * @param exitCodeEnabled the exitCodeEnabled to set
	 */
	public void setExitCodeEnabled(boolean exitCodeEnabled) {
		this.exitCodeEnabled = exitCodeEnabled;
	}

	/**
	 * @param warningAsError the warningAsError to set
	 */
	public void setWarningAsError(boolean warningAsError) {
		this.warningAsError = warningAsError;
	}

	/**
	 * @return the operators
	 */
	public Map<String, Operator> getOperators() {
		return operators;
	}

	/**
	 * @return the checkPaths
	 */
	public boolean isCheckPaths() {
		return checkPaths;
	}

	/**
	 * @param checkPaths the checkPaths to set
	 */
	public void setCheckPaths(boolean checkPaths) {
		this.checkPaths = checkPaths;
	}

	/**
	 * @return the rootPath
	 */
	public String getRootPath() {
		return rootPath;
	}

	/**
	 * @param rootPath the rootPath to set
	 */
	public void setRootPath(String rootPath) {
		this.rootPath = rootPath;
	}

	/**
	 * @param preprocessor 
	 */
	public void setPreprocessor(SQFPreprocessor preprocessor) {
		this.preprocessor = preprocessor;
	}

	/**
	 * @return the preprocessor
	 */
	public SQFPreprocessor getPreprocessor() {
		return preprocessor;
	}
}
