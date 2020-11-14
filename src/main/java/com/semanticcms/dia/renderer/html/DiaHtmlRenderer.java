/*
 * semanticcms-dia-renderer-html - Dia-based diagrams embedded in HTML in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of semanticcms-dia-renderer-html.
 *
 * semanticcms-dia-renderer-html is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * semanticcms-dia-renderer-html is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with semanticcms-dia-renderer-html.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.semanticcms.dia.renderer.html;

import com.aoindustries.awt.image.ImageSizeCache;
import com.aoindustries.concurrent.ConcurrencyLimiter;
import com.aoindustries.encoding.MediaWriter;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.encodeTextInXhtmlAttribute;
import com.aoindustries.exception.WrappedException;
import com.aoindustries.html.Html;
import com.aoindustries.lang.ProcessResult;
import com.aoindustries.net.URIEncoder;
import com.aoindustries.servlet.lastmodified.LastModifiedServlet;
import com.aoindustries.util.Sequence;
import com.aoindustries.util.UnsynchronizedSequence;
import com.aoindustries.util.concurrent.ExecutionExceptions;
import com.semanticcms.core.controller.ConcurrencyCoordinator;
import com.semanticcms.core.controller.ResourceRefResolver;
import com.semanticcms.core.controller.SemanticCMS;
import com.semanticcms.core.model.BookRef;
import com.semanticcms.core.model.ResourceRef;
import com.semanticcms.core.pages.CaptureLevel;
import com.semanticcms.core.pages.local.CurrentCaptureLevel;
import com.semanticcms.core.renderer.html.PageIndex;
import com.semanticcms.core.resources.Resource;
import com.semanticcms.core.resources.ResourceConnection;
import com.semanticcms.core.resources.ResourceStore;
import com.semanticcms.dia.model.Dia;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final public class DiaHtmlRenderer {

	private static final String LINUX_DIA_PATH = "/usr/bin/dia";

	private static final String WINDOWS_DIA_PATH = "C:\\Program Files (x86)\\Dia\\bin\\dia.exe";

	// This was used for opening the diagram, moved to semanticcms-openfile-servlet to avoid dependency.
	//private static final String WINDOWS_DIAW_PATH = "C:\\Program Files (x86)\\Dia\\bin\\diaw.exe";

	private static class TempDirLock {}
	private static final TempDirLock tempDirLock = new TempDirLock();
	private static final String TEMP_SUBDIR = DiaExport.class.getName();

	private static final String MISSING_IMAGE_PATH = "/semanticcms-dia-renderer-html/images/broken-chain-1164481-640x480.jpg";
	private static final int MISSING_IMAGE_WIDTH = 640;
	private static final int MISSING_IMAGE_HEIGHT = 480;

	public static final char SIZE_SEPARATOR = '-';
	public static final char EMPTY_SIZE = '_';
	public static final char DIMENSION_SEPARATOR = 'x';
	public static final String PNG_EXTENSION = ".png";

	/**
	 * Allows a range of last modified to vary, since platforms may not store millisecond
	 * accurate timestamps.
	 */
	private static final int FILESYSTEM_TIMESTAMP_TOLERANCE = 1000;

	/**
	 * The request key used to ensure per-request unique element IDs.
	 */
	private static final String ID_SEQUENCE_REQUEST_ATTRIBUTE = DiaHtmlRenderer.class.getName() + ".idSequence";

	/**
	 * The alt link ID prefix.
	 */
	private static final String ALT_LINK_ID_PREFIX = "semanticcms-dia-renderer-html-alt-pixel-ratio-";

	/**
	 * The default width when neither width nor height provided.
	 */
	private static final int DEFAULT_WIDTH = 200;

	/**
	 * The supported pixel densities, these must be ordered from lowest to highest.  The first is the default.
	 */
	private static final int[] PIXEL_DENSITIES = {
		1,
		2,
		3,
		4
	};

	private static boolean isWindows() {
		String osName = System.getProperty("os.name");
		return osName!=null && osName.toLowerCase(Locale.ROOT).contains("windows");
	}

	private static String getDiaExportPath() {
		if(isWindows()) {
			return WINDOWS_DIA_PATH;
		} else {
			return LINUX_DIA_PATH;
		}
	}

	/* This was used for opening the diagram, moved to semanticcms-openfile-servlet to avoid dependency.
	public static String getDiaOpenPath() {
		if(isWindows()) {
			return WINDOWS_DIAW_PATH;
		} else {
			return LINUX_DIA_PATH;
		}
	}
	 */

	/**
	 * Make sure each diagram and scaling is only exported once when under concurrent access.
	 */
	private static final ConcurrencyLimiter<File,Void> exportConcurrencyLimiter = new ConcurrencyLimiter<>();

	public static DiaExport exportDiagram(
		ServletContext servletContext,
		ResourceRef resourceRef,
		final Integer width,
		final Integer height,
		File tmpDir
	) throws InterruptedException, FileNotFoundException, IOException {
		final Resource resource = SemanticCMS
			.getInstance(servletContext)
			.getBook(resourceRef.getBookRef())
			.getResources()
			.getResource(resourceRef.getPath())
		;
		return exportDiagram(servletContext, resourceRef, resource, width, height, tmpDir);
	}

	public static DiaExport exportDiagram(
		ServletContext servletContext,
		ResourceRef resourceRef,
		final Resource resource,
		final Integer width,
		final Integer height,
		File tmpDir
	) throws InterruptedException, FileNotFoundException, IOException {
		BookRef bookRef = resourceRef.getBookRef();
		String diaPath = resourceRef.getPath().toString();
		// Strip extension if matches expected value
		if(diaPath.toLowerCase(Locale.ROOT).endsWith(Dia.DOT_EXTENSION)) {
			diaPath = diaPath.substring(0, diaPath.length() - Dia.DOT_EXTENSION.length());
		}
		// Generate the temp filename
		final File tmpFile;
		synchronized(tempDirLock) {
			tmpFile = new File(
				tmpDir,
				TEMP_SUBDIR
					+ bookRef.getPrefix().replace('/', File.separatorChar)
					+ diaPath.replace('/', File.separatorChar)
					+ "-"
					+ (width==null ? "_" : width.toString())
					+ "x"
					+ (height==null ? "_" : height.toString())
					+ PNG_EXTENSION
			);
			// Make temp directory if needed (and all parents)
			tmpDir = tmpFile.getParentFile();
			if(!tmpDir.exists()) Files.createDirectories(tmpDir.toPath());
		}
		// Re-export when missing or timestamps indicate needs recreated
		try {
			exportConcurrencyLimiter.executeSerialized(
				tmpFile,
				() -> {
					try (ResourceConnection conn = resource.open()) {
						// TODO: Handle 0 for unknown last modified, similar to properties file auto-loaded by JSP
						long resourceLastModified = conn.getLastModified();
						boolean scaleNow;
						if(!tmpFile.exists()) {
							scaleNow = true;
						} else {
							// TODO: Handle 0 for unknown last modified, similar to properties file auto-loaded by JSP
							long timeDiff = resourceLastModified - tmpFile.lastModified();
							scaleNow =
								timeDiff >= FILESYSTEM_TIMESTAMP_TOLERANCE
								|| timeDiff <= -FILESYSTEM_TIMESTAMP_TOLERANCE // system time reset?
							;
						}
						if(scaleNow) {
							// Determine size for scaling
							final String sizeParam;
							if(width==null) {
								if(height==null) {
									sizeParam = null;
								} else {
									sizeParam = "x" + height;
								}
							} else {
								if(height==null) {
									sizeParam = width + "x";
								} else {
									sizeParam = width + "x" + height;
								}
							}
							// Get the file
							File diaFile = conn.getFile();

							// Build the command
							final String diaExePath = getDiaExportPath();
							final String[] command;
							if(sizeParam == null) {
								command = new String[] {
									diaExePath,
									"--export=" + tmpFile.getCanonicalPath(),
									"--filter=png",
									"--log-to-stderr",
									diaFile.getCanonicalPath()
								};
							} else {
								command = new String[] {
									diaExePath,
									"--export=" + tmpFile.getCanonicalPath(),
									"--filter=png",
									"--size=" + sizeParam,
									"--log-to-stderr",
									diaFile.getCanonicalPath()
								};
							}
							// Export using dia
							ProcessResult result = ProcessResult.exec(command);
							int exitVal = result.getExitVal();
							if(exitVal != 0) throw new IOException(diaExePath + ": non-zero exit value: " + exitVal);
							if(!isWindows()) {
								// Dia does not set non-zero exit value, instead, it writes both errors and normal output to stderr
								// (Dia version 0.97.2, compiled 23:51:04 Apr 13 2012)
								String normalOutput = diaFile.getCanonicalPath() + " --> " + tmpFile.getCanonicalPath();
								// Read the standard error, if any one line matches the expected line, then it is OK
								// other lines include stuff like: Xlib:  extension "RANDR" missing on display ":0".
								boolean foundNormalOutput = false;
								String stderr = result.getStderr();
								try (BufferedReader errIn = new BufferedReader(new StringReader(stderr))) {
									String line;
									while((line = errIn.readLine())!=null) {
										if(line.equals(normalOutput)) {
											foundNormalOutput = true;
											break;
										}
									}
								}
								if(!foundNormalOutput) {
									throw new IOException(diaExePath + ": " + stderr);
								}
							}
							tmpFile.setLastModified(resourceLastModified);
						}
						return null;
					}
				}
			);
		} catch(ExecutionException e) {
			// Maintain expected exception types while not losing stack trace
			// TODO: Make a specialization for IOException, like done for SQLException?
			ExecutionExceptions.wrapAndThrowWithCause(e, FileNotFoundException.class, (eeCause, ee) -> {
				FileNotFoundException fnf = new FileNotFoundException(eeCause.getMessage());
				fnf.initCause(ee);
				return fnf;
			});
			ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
			throw new WrappedException(e);
		}
		// Get actual dimensions
		Dimension pngSize = ImageSizeCache.getImageSize(tmpFile);

		return new DiaExport(
			tmpFile,
			pngSize.width,
			pngSize.height
		);
	}

	private static String buildUrlPath(
		HttpServletRequest request,
		ResourceRef resourceRef,
		int width,
		int height,
		int pixelDensity,
		DiaExport export
	) throws ServletException {
		String diaPath = resourceRef.getPath().toString();
		// Strip extension
		if(!diaPath.endsWith(Dia.DOT_EXTENSION)) throw new ServletException("Unexpected file extension for diagram: " + diaPath);
		diaPath = diaPath.substring(0, diaPath.length() - Dia.DOT_EXTENSION.length());
		StringBuilder urlPath = new StringBuilder();
		urlPath
			.append(request.getContextPath())
			.append(DiaExportServlet.SERVLET_PATH)
			.append(resourceRef.getBookRef().getPrefix())
			.append(diaPath)
			.append(SIZE_SEPARATOR);
		if(width == 0) {
			urlPath.append(EMPTY_SIZE);
		} else {
			urlPath.append(width * pixelDensity);
		}
		urlPath.append(DIMENSION_SEPARATOR);
		if(height == 0) {
			urlPath.append(EMPTY_SIZE);
		} else {
			urlPath.append(height * pixelDensity);
		}
		urlPath.append(PNG_EXTENSION);
		// Check for header disabling auto last modified
		if(!"false".equalsIgnoreCase(request.getHeader(LastModifiedServlet.LAST_MODIFIED_HEADER_NAME))) {
			long lastModified = export.getTmpFile().lastModified();
			if(lastModified != 0) {
				urlPath
					.append('?')
					.append(LastModifiedServlet.LAST_MODIFIED_PARAMETER_NAME)
					.append('=')
					.append(LastModifiedServlet.encodeLastModified(lastModified))
				;
			}
		}
		return urlPath.toString();
	}

	public static void writeDiaImpl(
		final ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Html html,
		Dia dia
	) throws ServletException, IOException {
		try {
			// Get the current capture state
			final CaptureLevel captureLevel = CurrentCaptureLevel.getCaptureLevel(request);
			if(captureLevel.compareTo(CaptureLevel.META) >= 0) {
				final ResourceRef resourceRef = ResourceRefResolver.getResourceRef(servletContext, request, dia.getDomain(), dia.getBook(), dia.getPath());
				if(captureLevel == CaptureLevel.BODY) {
					// Use default width when neither provided
					int width = dia.getWidth();
					int height = dia.getHeight();
					if(width==0 && height==0) width = DEFAULT_WIDTH;
					final Resource resource;
					{
						ResourceStore restoreStore = SemanticCMS
							.getInstance(servletContext)
							.getBook(resourceRef.getBookRef())
							.getResources()
						;
						if(restoreStore == null || !restoreStore.isAvailable()) {
							resource = null;
						} else {
							Resource r = restoreStore.getResource(resourceRef.getPath());
							if(!r.exists()) r = null;
							resource = r;
						}
					}
					// Scale concurrently for each pixel density
					List<DiaExport> exports;
					if(resource == null) {
						exports = null;
					} else {
						final File tempDir = (File)servletContext.getAttribute("javax.servlet.context.tempdir" /*ServletContext.TEMPDIR*/);
						final int finalWidth = width;
						final int finalHeight = height;
						// TODO: Avoid concurrent tasks when all diagrams are already up-to-date?
						// TODO: Fetch resource file once when first needed?
						List<Callable<DiaExport>> tasks = new ArrayList<>(PIXEL_DENSITIES.length);
						for(int i=0; i<PIXEL_DENSITIES.length; i++) {
							final int pixelDensity = PIXEL_DENSITIES[i];
							tasks.add(
								() -> exportDiagram(
									servletContext,
									resourceRef,
									resource,
									finalWidth==0 ? null : (finalWidth * pixelDensity),
									finalHeight==0 ? null : (finalHeight * pixelDensity),
									tempDir
								)
							);
						}
						try {
							exports = ConcurrencyCoordinator.getRecommendedExecutor(servletContext, request).callAll(tasks);
						} catch(ExecutionException e) {
							// Maintain expected exception types while not losing stack trace
							ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
							throw new ServletException(e);
						}
					}
					// Get the thumbnail image in default pixel density
					DiaExport export = exports == null ? null : exports.get(0);
					// Find id sequence
					Sequence idSequence = (Sequence)request.getAttribute(ID_SEQUENCE_REQUEST_ATTRIBUTE);
					if(idSequence == null) {
						idSequence = new UnsynchronizedSequence();
						request.setAttribute(ID_SEQUENCE_REQUEST_ATTRIBUTE, idSequence);
					}
					// Write the img tag
					String refId = PageIndex.getRefIdInPage(request, dia.getPage(), dia.getId());
					final String urlPath;
					if(export != null) {
						urlPath = buildUrlPath(
							request,
							resourceRef,
							width,
							height,
							PIXEL_DENSITIES[0],
							export
						);
					} else {
						urlPath =
							request.getContextPath()
							+ MISSING_IMAGE_PATH
						;
					}
					html.img()
						.id(refId)
						.src(response.encodeURL(URIEncoder.encodeURI(urlPath)))
						.width(
							export!=null
							? (export.getWidth() / PIXEL_DENSITIES[0])
							: width!=0
							? width
							: (MISSING_IMAGE_WIDTH * height / MISSING_IMAGE_HEIGHT)
						).height(
							export!=null
							? (export.getHeight() / PIXEL_DENSITIES[0])
							: height!=0
							? height
							: (MISSING_IMAGE_HEIGHT * width / MISSING_IMAGE_WIDTH)
						).alt(dia.getLabel())
						.__();
					//if(resourceFile == null) {
					//	LinkImpl.writeBrokenPathInXhtmlAttribute(pageRef, out);
					//} else {
					//	encodeTextInXhtmlAttribute(resourceFile.getName(), out);
					//}

					if(export != null && PIXEL_DENSITIES.length > 1) {
						assert resource != null && resource.exists();
						assert exports != null;
						// Write links to the exports for higher pixel densities
						long[] altLinkNums = new long[PIXEL_DENSITIES.length];
						for(int i=0; i<PIXEL_DENSITIES.length; i++) {
							int pixelDensity = PIXEL_DENSITIES[i];
							// Get the thumbnail image in alternate pixel density
							DiaExport altExport = exports.get(i);
							// Write the a tag to additional pixel densities
							html.out.write("<a id=\"" + ALT_LINK_ID_PREFIX);
							long altLinkNum = idSequence.getNextSequenceValue();
							altLinkNums[i] = altLinkNum;
							encodeTextInXhtmlAttribute(Long.toString(altLinkNum), html.out);
							html.out.write("\" style=\"display:none\" href=\"");
							final String altUrlPath = buildUrlPath(
								request,
								resourceRef,
								width,
								height,
								pixelDensity,
								altExport
							);
							encodeTextInXhtmlAttribute(
								response.encodeURL(URIEncoder.encodeURI(altUrlPath)),
								html.out
							);
							html.out.write("\">x");
							html.text(pixelDensity);
							html.out.write("</a>");
						}
						// Write script to hide alt links and select best based on device pixel ratio
						try (MediaWriter script = html.script().out__()) {
							// hide alt links
							//for(int i=1; i<PIXEL_DENSITIES.length; i++) {
							//	long altLinkNum = altLinkNums[i];
							//	scriptOut
							//		.write("document.getElementById(\"" + ALT_LINK_ID_PREFIX)
							//		.write(Long.toString(altLinkNum))
							//		.write("\").style.display = \"none\";\n");
							//}
							// select best based on device pixel ratio
							script.write("if(window.devicePixelRatio) {\n");
							// Closure for locally scoped variables
							script.write("\t(function () {\n");
							// scriptOut.write("\twindow.alert(\"devicePixelRatio=\" + window.devicePixelRatio);\n");
							// Function to update src
							script.write("\t\tfunction updateImageSrc() {\n");
							for(int i=PIXEL_DENSITIES.length - 1; i >= 0; i--) {
								long altLinkNum = altLinkNums[i];
								script.write("\t\t\t");
								if(i != (PIXEL_DENSITIES.length - 1)) script.write("else ");
								if(i > 0) {
									script.write("if(window.devicePixelRatio > ");
									script.write(Integer.toString(PIXEL_DENSITIES[i-1]));
									script.write(") ");
								}
								script.append("{\n"
										+ "\t\t\t\tdocument.getElementById(").text(refId).append("\").src = document.getElementById(\"" + ALT_LINK_ID_PREFIX).append(Long.toString(altLinkNum)).append("\").getAttribute(\"href\");\n"
										+ "\t\t\t}\n");
							}
							script.write("\t\t}\n"
								// Perform initial setup
								+ "\t\tupdateImageSrc();\n");
							// Change image source when pixel ratio changes
							script.write("\t\tif(window.matchMedia) {\n");
							for(int i=0; i<PIXEL_DENSITIES.length; i++) {
								int pixelDensity = PIXEL_DENSITIES[i];
								script.write("\t\t\twindow.matchMedia(\"screen and (max-resolution: ");
								script.write(Integer.toString(pixelDensity));
								script.write("dppx)\").addListener(function(e) {\n"
										+ "\t\t\t\tupdateImageSrc();\n"
										+ "\t\t\t});\n");
							}
							script.write("\t\t}\n"
								+ "\t})();\n"
								+ "}\n");
						}
					}
				}
			}
		} catch(InterruptedException e) {
			throw new ServletException(e);
		}
	}

	/**
	 * Make no instances.
	 */
	private DiaHtmlRenderer() {
	}
}
