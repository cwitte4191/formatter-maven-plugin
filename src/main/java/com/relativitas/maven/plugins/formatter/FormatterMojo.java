/**
 * Copyright 2010-2014. All work is copyrighted to their respective
 * author(s), unless otherwise stated.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.relativitas.maven.plugins.formatter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.io.fileselectors.FileSelector;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.codehaus.plexus.components.io.resources.PlexusIoFileResource;
import org.codehaus.plexus.components.io.resources.PlexusIoFileResourceCollection;
import org.codehaus.plexus.components.io.resources.PlexusIoResource;
import org.codehaus.plexus.resource.ResourceManager;
import org.codehaus.plexus.resource.loader.FileResourceLoader;
import org.codehaus.plexus.resource.loader.ResourceNotFoundException;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.WriterFactory;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;
import org.xml.sax.SAXException;

/**
 * A Maven plugin mojo to format Java source code using the Eclipse code
 * formatter.
 * 
 * Mojo parameters allow customizing formatting by specifying the config XML
 * file, line endings, compiler version, and source code locations. Reformatting
 * source files is avoided using an md5 hash of the content, comparing to the
 * original hash to the hash after formatting and a cached hash.
 * 
 * @author jecki
 * @author Matt Blanchette
 */
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class FormatterMojo extends AbstractMojo {
	
	/** The Constant CACHE_PROPERTIES_FILENAME. */
	private static final String CACHE_PROPERTIES_FILENAME = "maven-java-formatter-cache.properties";
	
	/** The Constant DEFAULT_INCLUDES. */
	private static final String[] DEFAULT_INCLUDES = new String[] { "**/*.java" };

	/** The Constant LINE_ENDING_AUTO. */
	static final String LINE_ENDING_AUTO = "AUTO";
	
	/** The Constant LINE_ENDING_KEEP. */
	static final String LINE_ENDING_KEEP = "KEEP";
	
	/** The Constant LINE_ENDING_LF. */
	static final String LINE_ENDING_LF = "LF";
	
	/** The Constant LINE_ENDING_CRLF. */
	static final String LINE_ENDING_CRLF = "CRLF";
	
	/** The Constant LINE_ENDING_CR. */
	static final String LINE_ENDING_CR = "CR";

	/** The Constant LINE_ENDING_LF_CHAR. */
	static final String LINE_ENDING_LF_CHAR = "\n";
	
	/** The Constant LINE_ENDING_CRLF_CHARS. */
	static final String LINE_ENDING_CRLF_CHARS = "\r\n";
	
	/** The Constant LINE_ENDING_CR_CHAR. */
	static final String LINE_ENDING_CR_CHAR = "\r";

	/**
	 * ResourceManager for retrieving the configFile resource.
	 */
	@Component(role=ResourceManager.class)
	private ResourceManager resourceManager;

	/**
	 * Project's source directory as specified in the POM.
	 */
	@Parameter(property = "project.build.sourceDirectory", readonly = true, required = true)
	private File sourceDirectory;

	/**
	 * Project's test source directory as specified in the POM.
	 */
	@Parameter(property = "project.build.testSourceDirectory", readonly = true, required = true)
	private File testSourceDirectory;

	/**
	 * Project's target directory as specified in the POM.
	 */
	@Parameter(property = "project.build.directory", readonly = true, required = true)
	private File targetDirectory;

	/**
	 * Project's base directory.
	 */
	@Parameter(property = "project.basedir", readonly = true, required = true)
	private File basedir;

	/**
	 * Location of the Java source files to format. Defaults to source main and
	 * test directories if not set. Deprecated in version 0.3. Reintroduced in
	 * 0.4.
	 * 
	 * @since 0.4
	 */
	@Parameter
	private File[] directories;

	/**
	 * List of fileset patterns for Java source locations to include in
	 * formatting. Patterns are relative to the project source and test source
	 * directories. When not specified, the default include is
	 * <code>**&#47;*.java</code>
	 * 
	 * @since 0.3
	 */
	@Parameter
	private String[] includes;

	/**
	 * List of fileset patterns for Java source locations to exclude from
	 * formatting. Patterns are relative to the project source and test source
	 * directories. When not specified, there is no default exclude.
	 * 
	 * @since 0.3
	 */
	@Parameter
	private String[] excludes;

	/**
	 * Java compiler source version.
	 */
	@Parameter(defaultValue = "1.5", property = "maven.compiler.source")
	private String compilerSource;

	/**
	 * Java compiler compliance version.
	 */
	@Parameter(defaultValue = "1.5", property = "maven.compiler.source")
	private String compilerCompliance;

	/**
	 * Java compiler target version.
	 */
	@Parameter(defaultValue = "1.5", property = "maven.compiler.target")
	private String compilerTargetPlatform;

	/**
	 * The file encoding used to read and write source files. When not specified
	 * and sourceEncoding also not set, default is platform file encoding.
	 * 
	 * @since 0.3
	 */
	@Parameter(defaultValue = "${project.build.sourceEncoding}")
	private String encoding;

	/**
	 * Sets the line-ending of files after formatting. Valid values are:
	 * <ul>
	 * <li><b>"AUTO"</b> - Use line endings of current system</li>
	 * <li><b>"KEEP"</b> - Preserve line endings of files, default to AUTO if
	 * ambiguous</li>
	 * <li><b>"LF"</b> - Use Unix and Mac style line endings</li>
	 * <li><b>"CRLF"</b> - Use DOS and Windows style line endings</li>
	 * <li><b>"CR"</b> - Use early Mac style line endings</li>
	 * </ul>
	 * 
	 * @since 0.2.0
	 */
	@Parameter(defaultValue = "AUTO")
	private String lineEnding;

	/**
	 * File or classpath location of an Eclipse code formatter configuration xml
	 * file to use in formatting.
	 */
	@Parameter
	private String configFile;

	/**
	 * Sets whether compilerSource, compilerCompliance, and
	 * compilerTargetPlatform values are used instead of those defined in the
	 * configFile.
	 * 
	 * @since 0.2.0
	 */
	@Parameter(defaultValue = "false")
	private Boolean overrideConfigCompilerVersion;

	/**
	 * Whether the formatting is skipped.
	 *
	 * @since 0.5
	 */
	@Parameter(defaultValue = "false", alias = "skip", property = "formatter.skip")
	private Boolean skipFormatting;

	/** The formatter. */
	private CodeFormatter formatter;

	/** The collection. */
	private PlexusIoFileResourceCollection collection;

	/**
	 * Execute.
	 *
	 * @throws MojoExecutionException the mojo execution exception
	 * @see org.apache.maven.plugin.AbstractMojo#execute()
	 */
	@Override
	public void execute() throws MojoExecutionException {
		if (this.skipFormatting) {
			getLog().info("Formatting is skipped");
			return;
		}
		long startClock = System.currentTimeMillis();

		if (StringUtils.isEmpty(this.encoding)) {
			this.encoding = ReaderFactory.FILE_ENCODING;
			getLog().warn(
					"File encoding has not been set, using platform encoding ("
							+ this.encoding
							+ ") to format source files, i.e. build is platform dependent!");
		} else {
			try {
				"Test Encoding".getBytes(this.encoding);
			} catch (UnsupportedEncodingException e) {
				throw new MojoExecutionException("Encoding '" + this.encoding
						+ "' is not supported");
			}
			getLog().info(
					"Using '" + this.encoding + "' encoding to format source files.");
		}

		if (!LINE_ENDING_AUTO.equals(this.lineEnding)
				&& !LINE_ENDING_KEEP.equals(this.lineEnding)
				&& !LINE_ENDING_LF.equals(this.lineEnding)
				&& !LINE_ENDING_CRLF.equals(this.lineEnding)
				&& !LINE_ENDING_CR.equals(this.lineEnding)) {
			throw new MojoExecutionException(
					"Unknown value for lineEnding parameter");
		}

		createResourceCollection();

		List<File> files = new ArrayList<File>();
		try {
			if (this.directories != null) {
				for (File directory : this.directories) {
					if (directory.exists() && directory.isDirectory()) {
						this.collection.setBaseDir(directory);
						addCollectionFiles(files);
					}
				}
			} else { // Using defaults of source main and test dirs
				if (this.sourceDirectory != null && this.sourceDirectory.exists()
						&& this.sourceDirectory.isDirectory()) {
					this.collection.setBaseDir(this.sourceDirectory);
					addCollectionFiles(files);
				}
				if (this.testSourceDirectory != null && this.testSourceDirectory.exists()
						&& this.testSourceDirectory.isDirectory()) {
					this.collection.setBaseDir(this.testSourceDirectory);
					addCollectionFiles(files);
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException(
					"Unable to find files using includes/excludes", e);
		}

		int numberOfFiles = files.size();
		Log log = getLog();
		log.info("Number of files to be formatted: " + numberOfFiles);

		if (numberOfFiles > 0) {
			createCodeFormatter();
			ResultCollector rc = new ResultCollector();
			Properties hashCache = readFileHashCacheFile();

			String basedirPath = getBasedirPath();
			for (int i = 0, n = files.size(); i < n; i++) {
				File file = files.get(i);
				formatFile(file, rc, hashCache, basedirPath);
			}

			storeFileHashCache(hashCache);

			long endClock = System.currentTimeMillis();

			log.info("Successfully formatted: " + rc.successCount + " file(s)");
			log.info("Fail to format        : " + rc.failCount + " file(s)");
			log.info("Skipped               : " + rc.skippedCount + " file(s)");
			log.info("Approximate time taken: "
					+ ((endClock - startClock) / 1000) + "s");
		}
	}

	/**
	 * Create a {@link PlexusIoFileResourceCollection} instance to be used by
	 * this mojo. This collection uses the includes and excludes to find the
	 * source files.
	 */
	void createResourceCollection() {
		this.collection = new PlexusIoFileResourceCollection();
		if (this.includes != null && this.includes.length > 0) {
			this.collection.setIncludes(this.includes);
		} else {
			this.collection.setIncludes(DEFAULT_INCLUDES);
		}
		this.collection.setExcludes(this.excludes);
		this.collection.setIncludingEmptyDirectories(false);

		IncludeExcludeFileSelector fileSelector = new IncludeExcludeFileSelector();
		fileSelector.setIncludes(DEFAULT_INCLUDES);
		this.collection.setFileSelectors(new FileSelector[] { fileSelector });
	}

	/**
	 * Add source files from the {@link PlexusIoFileResourceCollection} to the
	 * files list.
	 *
	 * @param files the files
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	void addCollectionFiles(List<File> files) throws IOException {
		Iterator<PlexusIoResource> resources = this.collection.getResources();
		while (resources.hasNext()) {
			PlexusIoFileResource resource = (PlexusIoFileResource) resources
					.next();
			files.add(resource.getFile());
		}
	}

	/**
	 * Gets the basedir path.
	 *
	 * @return the basedir path
	 */
	private String getBasedirPath() {
		try {
			return this.basedir.getCanonicalPath();
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Store file hash cache.
	 *
	 * @param props the props
	 */
	private void storeFileHashCache(Properties props) {
		File cacheFile = new File(this.targetDirectory, CACHE_PROPERTIES_FILENAME);
		try {
			OutputStream out = new BufferedOutputStream(new FileOutputStream(
					cacheFile));
			props.store(out, null);
		} catch (FileNotFoundException e) {
			getLog().warn("Cannot store file hash cache properties file", e);
		} catch (IOException e) {
			getLog().warn("Cannot store file hash cache properties file", e);
		}
	}

	/**
	 * Read file hash cache file.
	 *
	 * @return the properties
	 */
	private Properties readFileHashCacheFile() {
		Properties props = new Properties();
		Log log = getLog();
		if (!this.targetDirectory.exists()) {
			this.targetDirectory.mkdirs();
		} else if (!this.targetDirectory.isDirectory()) {
			log.warn("Something strange here as the "
					+ "supposedly target directory is not a directory.");
			return props;
		}

		File cacheFile = new File(this.targetDirectory, CACHE_PROPERTIES_FILENAME);
		if (!cacheFile.exists()) {
			return props;
		}

		try {
			props.load(new BufferedInputStream(new FileInputStream(cacheFile)));
		} catch (FileNotFoundException e) {
			log.warn("Cannot load file hash cache properties file", e);
		} catch (IOException e) {
			log.warn("Cannot load file hash cache properties file", e);
		}
		return props;
	}

	/**
	 * Format file.
	 *
	 * @param file the file
	 * @param rc the rc
	 * @param hashCache the hash cache
	 * @param basedirPath the basedir path
	 */
	private void formatFile(File file, ResultCollector rc,
			Properties hashCache, String basedirPath) {
		try {
			doFormatFile(file, rc, hashCache, basedirPath);
		} catch (IOException e) {
			rc.failCount++;
			getLog().warn(e);
		} catch (MalformedTreeException e) {
			rc.failCount++;
			getLog().warn(e);
		} catch (BadLocationException e) {
			rc.failCount++;
			getLog().warn(e);
		}
	}

	/**
	 * Format individual file.
	 *
	 * @param file the file
	 * @param rc the rc
	 * @param hashCache the hash cache
	 * @param basedirPath the basedir path
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws BadLocationException the bad location exception
	 */
	private void doFormatFile(File file, ResultCollector rc,
			Properties hashCache, String basedirPath) throws IOException,
			BadLocationException {
		Log log = getLog();
		log.debug("Processing file: " + file);
		String code = readFileAsString(file);
		String originalHash = md5hash(code);

		String canonicalPath = file.getCanonicalPath();
		String path = canonicalPath.substring(basedirPath.length());
		String cachedHash = hashCache.getProperty(path);
		if (cachedHash != null && cachedHash.equals(originalHash)) {
			rc.skippedCount++;
			log.debug("File is already formatted.");
			return;
		}

		String lineSeparator = getLineEnding(code);

		TextEdit te = this.formatter.format(CodeFormatter.K_COMPILATION_UNIT
				+ CodeFormatter.F_INCLUDE_COMMENTS, code, 0, code.length(), 0,
				lineSeparator);
		if (te == null) {
			rc.skippedCount++;
			log.debug("Code cannot be formatted. Possible cause "
					+ "is unmatched source/target/compliance version.");
			return;
		}

		IDocument doc = new Document(code);
		te.apply(doc);
		String formattedCode = doc.get();
		String formattedHash = md5hash(formattedCode);
		hashCache.setProperty(path, formattedHash);

		if (originalHash.equals(formattedHash)) {
			rc.skippedCount++;
			log.debug("Equal hash code. Not writing result to file.");
			return;
		}

		writeStringToFile(formattedCode, file);
		rc.successCount++;
	}

	/**
	 * Md5hash.
	 *
	 * @param str the str
	 * @return the string
	 * @throws UnsupportedEncodingException the unsupported encoding exception
	 */
	private String md5hash(String str) throws UnsupportedEncodingException {
		return DigestUtils.md5Hex(str.getBytes(this.encoding));
	}

	/**
	 * Read the given file and return the content as a string.
	 *
	 * @param file the file
	 * @return the string
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private String readFileAsString(File file) throws java.io.IOException {
		StringBuilder fileData = new StringBuilder(1000);
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(ReaderFactory.newReader(file, this.encoding));
			char[] buf = new char[1024];
			int numRead = 0;
			while ((numRead = reader.read(buf)) != -1) {
				String readData = String.valueOf(buf, 0, numRead);
				fileData.append(readData);
				buf = new char[1024];
			}
		} finally {
			IOUtil.close(reader);
		}
		return fileData.toString();
	}

	/**
	 * Write the given string to a file.
	 *
	 * @param str the str
	 * @param file the file
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private void writeStringToFile(String str, File file) throws IOException {
		if (!file.exists() && file.isDirectory()) {
			return;
		}

		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(WriterFactory.newWriter(file, this.encoding));
			bw.write(str);
		} finally {
			IOUtil.close(bw);
		}
	}

	/**
	 * Create a {@link CodeFormatter} instance to be used by this mojo.
	 *
	 * @throws MojoExecutionException the mojo execution exception
	 */
	private void createCodeFormatter() throws MojoExecutionException {
		Map<String, String> options = getFormattingOptions();
		this.formatter = ToolFactory.createCodeFormatter(options);
	}

	/**
	 * Return the options to be passed when creating {@link CodeFormatter}
	 * instance.
	 *
	 * @return the formatting options
	 * @throws MojoExecutionException the mojo execution exception
	 */
	private Map<String, String> getFormattingOptions()
			throws MojoExecutionException {
		Map<String, String> options = new HashMap<String, String>();
		options.put(JavaCore.COMPILER_SOURCE, this.compilerSource);
		options.put(JavaCore.COMPILER_COMPLIANCE, this.compilerCompliance);
		options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM,
				this.compilerTargetPlatform);

		if (this.configFile != null) {
			Map<String, String> config = getOptionsFromConfigFile();
			if (Boolean.TRUE.equals(this.overrideConfigCompilerVersion)) {
				config.remove(JavaCore.COMPILER_SOURCE);
				config.remove(JavaCore.COMPILER_COMPLIANCE);
				config.remove(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM);
			}
			options.putAll(config);
		}

		return options;
	}

	/**
	 * Read config file and return the config as {@link Map}.
	 *
	 * @return the options from config file
	 * @throws MojoExecutionException the mojo execution exception
	 */
	private Map<String, String> getOptionsFromConfigFile()
			throws MojoExecutionException {

		InputStream configInput = null;
		try {
			this.resourceManager.addSearchPath(FileResourceLoader.ID,
					this.basedir.getAbsolutePath());
			configInput = this.resourceManager.getResourceAsInputStream(this.configFile);
		} catch (ResourceNotFoundException e) {
			throw new MojoExecutionException("Config file [" + this.configFile
					+ "] cannot be found", e);
		}

		if (configInput == null) {
			throw new MojoExecutionException("Config file [" + this.configFile
					+ "] does not exist");
		}
		try {
			ConfigReader configReader = new ConfigReader();
			return configReader.read(configInput);
		} catch (IOException e) {
			throw new MojoExecutionException("Cannot read config file ["
					+ this.configFile + "]", e);
		} catch (SAXException e) {
			throw new MojoExecutionException("Cannot parse config file ["
					+ this.configFile + "]", e);
		} catch (ConfigReadException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		} finally {
			if (configInput != null) {
				try {
					configInput.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	/**
	 * Returns the lineEnding parameter as characters when the value is known
	 * (LF, CRLF, CR) or can be determined from the file text (KEEP). Otherwise
	 * null is returned.
	 *
	 * @param fileDataString the file data string
	 * @return the line ending
	 */
	String getLineEnding(String fileDataString) {
		String lineEnd = null;
		if (LINE_ENDING_KEEP.equals(this.lineEnding)) {
			lineEnd = determineLineEnding(fileDataString);
		} else if (LINE_ENDING_LF.equals(this.lineEnding)) {
			lineEnd = LINE_ENDING_LF_CHAR;
		} else if (LINE_ENDING_CRLF.equals(this.lineEnding)) {
			lineEnd = LINE_ENDING_CRLF_CHARS;
		} else if (LINE_ENDING_CR.equals(this.lineEnding)) {
			lineEnd = LINE_ENDING_CR_CHAR;
		}
		return lineEnd;
	}

	/**
	 * Returns the most occurring line-ending characters in the file text or
	 * null if no line-ending occurs the most.
	 *
	 * @param fileDataString the file data string
	 * @return the string
	 */
	String determineLineEnding(String fileDataString) {
		int lfCount = 0;
		int crCount = 0;
		int crlfCount = 0;

		for (int i = 0; i < fileDataString.length(); i++) {
			char c = fileDataString.charAt(i);
			if (c == '\r') {
				if ((i + 1) < fileDataString.length()
						&& fileDataString.charAt(i + 1) == '\n') {
					crlfCount++;
					i++;
				} else {
					crCount++;
				}
			} else if (c == '\n') {
				lfCount++;
			}
		}
		if (lfCount > crCount && lfCount > crlfCount) {
			return LINE_ENDING_LF_CHAR;
		} else if (crlfCount > lfCount && crlfCount > crCount) {
			return LINE_ENDING_CRLF_CHARS;
		} else if (crCount > lfCount && crCount > crlfCount) {
			return LINE_ENDING_CR_CHAR;
		}
		return null;
	}

	/**
	 * The Class ResultCollector.
	 */
	private class ResultCollector {
		
		public ResultCollector() {
			// Prevent synthetic access
		}

		/** The success count. */
		int successCount;
		
		/** The fail count. */
		int failCount;
		
		/** The skipped count. */
		int skippedCount;
	}
}
