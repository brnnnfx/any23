/**
 * Copyright 2008-2010 Digital Enterprise Research Institute (DERI)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.deri.any23.extractor;

import org.deri.any23.encoding.EncodingDetector;
import org.deri.any23.encoding.TikaEncodingDetector;
import org.deri.any23.extractor.Extractor.BlindExtractor;
import org.deri.any23.extractor.Extractor.ContentExtractor;
import org.deri.any23.extractor.Extractor.TagSoupDOMExtractor;
import org.deri.any23.extractor.html.TagSoupParser;
import org.deri.any23.mime.MIMEType;
import org.deri.any23.mime.MIMETypeDetector;
import org.deri.any23.rdf.Any23ValueFactoryWrapper;
import org.deri.any23.source.DocumentSource;
import org.deri.any23.source.LocalCopyFactory;
import org.deri.any23.source.MemCopyFactory;
import org.deri.any23.writer.TripleHandler;
import org.openrdf.model.URI;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Collections;

/**
 * This class acts as facade where all the extractors were called on a single document.
 */
public class SingleDocumentExtraction {

    private final static Logger log = LoggerFactory.getLogger(SingleDocumentExtraction.class);

    private final DocumentSource in;

    private URI documentURI;
    
    private final ExtractorGroup extractors;

    private final TripleHandler output;

    private final EncodingDetector encoderDetector;

    private LocalCopyFactory copyFactory = null;

    private DocumentSource localDocumentSource = null;

    private MIMETypeDetector detector = null;

    private ExtractorGroup matchingExtractors = null;

    private MIMEType detectedMIMEType = null;

    private Document tagSoupDOM = null;

    private String parserEncoding = null;

    public SingleDocumentExtraction(DocumentSource in, ExtractorGroup extractors, TripleHandler output) {
        this.in = in;
        this.extractors = extractors;
        this.output = output;
        this.encoderDetector = new TikaEncodingDetector();
    }

    public SingleDocumentExtraction(DocumentSource in, ExtractorFactory<?> factory, TripleHandler output) {
        this(in, new ExtractorGroup(Collections.<ExtractorFactory<?>>singletonList(factory)),
                output);
        this.setMIMETypeDetector(null);
    }

    public void setLocalCopyFactory(LocalCopyFactory copyFactory) {
        this.copyFactory = copyFactory;
    }

    public void setMIMETypeDetector(MIMETypeDetector detector) {
        this.detector = detector;
    }

    /**
     * Triggers the execution of all the {@link org.deri.any23.extractor.Extractor} registered to this class.
     *
     * @throws ExtractionException
     * @throws IOException
     */
    public void run() throws ExtractionException, IOException {
        ensureHasLocalCopy();
        try {
            this.documentURI = new Any23ValueFactoryWrapper(
                    ValueFactoryImpl.getInstance()
            ).createURI( in.getDocumentURI() );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid URI: " + in.getDocumentURI(), ex);
        }
        if(log.isInfoEnabled()) {
            log.info("Processing " + this.documentURI);
        }
        filterExtractorsByMIMEType();

        if(log.isDebugEnabled()) {
            StringBuffer sb = new StringBuffer("Extractors ");
            for (ExtractorFactory<?> factory : matchingExtractors) {
                sb.append(factory.getExtractorName());
                sb.append(' ');
            }
            sb.append("match ").append(documentURI);
            log.debug(sb.toString());
        }

        // Invoke all extractors.
        output.startDocument(documentURI);
        output.setContentLength(in.getContentLength());
        for (ExtractorFactory<?> factory : matchingExtractors) {
            runExtractor(factory.createExtractor());
        }
        output.endDocument(documentURI);
    }

    public String getDetectedMIMEType() throws IOException {
        filterExtractorsByMIMEType();
        return detectedMIMEType.toString();
    }

    public boolean hasMatchingExtractors() throws IOException {
        filterExtractorsByMIMEType();
        return !matchingExtractors.isEmpty();
    }

    public String getParserEncoding() {
        return this.parserEncoding;
    }

    public void setParserEncoding(String encoding) {
        this.parserEncoding = encoding;
        tagSoupDOM = null;
    }

    private void filterExtractorsByMIMEType()
    throws IOException {
        if (matchingExtractors != null) return;  // has already been run.

        if (detector == null || extractors.allExtractorsSupportAllContentTypes()) {
            matchingExtractors = extractors;
            return;
        }
        ensureHasLocalCopy();
        detectedMIMEType = detector.guessMIMEType(
                java.net.URI.create(documentURI.stringValue()).getPath(),
                localDocumentSource.openInputStream(),
                MIMEType.parse(localDocumentSource.getContentType())
        );
        log.debug("detected media type: " + detectedMIMEType);
        matchingExtractors = extractors.filterByMIMEType(detectedMIMEType);
    }

    /**
     * Triggers the execution of a specific {@link org.deri.any23.extractor.Extractor}.
     * 
     * @param extractor the {@link org.deri.any23.extractor.Extractor} to be executed.
     * @throws ExtractionException
     * @throws IOException
     */
    private void runExtractor(Extractor<?> extractor)
    throws ExtractionException, IOException {
        if(log.isDebugEnabled()) {
            log.debug("Running " + extractor.getDescription().getExtractorName() + " on " + documentURI);
        }
        long startTime = System.currentTimeMillis();
        ExtractionResultImpl result = new ExtractionResultImpl(documentURI, extractor, output);
        try {
            if (extractor instanceof BlindExtractor) {
                ((BlindExtractor) extractor).run(documentURI, documentURI, result);
            } else if (extractor instanceof ContentExtractor) {
                ensureHasLocalCopy();
                ((ContentExtractor) extractor).run(localDocumentSource.openInputStream(), documentURI, result);
            } else if (extractor instanceof TagSoupDOMExtractor) {
                ((TagSoupDOMExtractor) extractor).run(getTagSoupDOM(), documentURI, result);
            } else {
                throw new RuntimeException("Extractor type not supported: " + extractor.getClass());
            }
        } catch (ExtractionException ex) {
            if(log.isInfoEnabled()) {
                log.info(extractor.getDescription().getExtractorName() + ": " + ex.getMessage());
            }
            throw ex;
        } finally {
            // Logging result error report.
            if( log.isInfoEnabled() && result.hasErrors() ) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                result.printErrorsReport( new PrintStream(baos) );
                log.info( baos.toString() );
            }
            result.close();

            long elapsed = System.currentTimeMillis() - startTime;
            if(log.isDebugEnabled()) {
                log.debug("Completed " + extractor.getDescription().getExtractorName() + ", " + elapsed + "ms");
            }
        }
    }

    private void ensureHasLocalCopy() throws IOException {
        if (localDocumentSource != null) return;
        if (in.isLocal()) {
            localDocumentSource = in;
            return;
        }
        if (copyFactory == null) {
            copyFactory = new MemCopyFactory();
        }
        localDocumentSource = copyFactory.createLocalCopy(in);
    }

    private Document getTagSoupDOM() throws IOException {
        if (tagSoupDOM == null) {
            ensureHasLocalCopy();
            final InputStream is = new BufferedInputStream( localDocumentSource.openInputStream() );
            is.mark(Integer.MAX_VALUE);
            final String candidateEncoding = getCandidateEncoding(is);
            is.reset();
            tagSoupDOM = new TagSoupParser(
                    is,
                    documentURI.stringValue(),
                    candidateEncoding
            ).getDOM();
        }
        return tagSoupDOM;
    }

    private String getCandidateEncoding(InputStream is) throws IOException {
        if(this.parserEncoding != null) {
            return this.parserEncoding;    
        }
        return this.encoderDetector.guessEncoding(is);

    }
    
}
