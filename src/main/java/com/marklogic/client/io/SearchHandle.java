/*
 * Copyright 2012 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.client.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Stack;

import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import com.marklogic.client.MarkLogicInternalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.marklogic.client.Format;
import com.marklogic.client.MarkLogicIOException;
import com.marklogic.client.config.FacetHeatmapValue;
import com.marklogic.client.config.FacetResult;
import com.marklogic.client.config.FacetValue;
import com.marklogic.client.config.MatchDocumentSummary;
import com.marklogic.client.config.MatchLocation;
import com.marklogic.client.config.MatchSnippet;
import com.marklogic.client.config.QueryDefinition;
import com.marklogic.client.config.SearchMetrics;
import com.marklogic.client.config.SearchResults;
import com.marklogic.client.io.marker.OperationNotSupported;
import com.marklogic.client.io.marker.SearchReadHandle;
import org.w3c.dom.Attr;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public class SearchHandle
	extends BaseHandle<InputStream, OperationNotSupported>
	implements SearchReadHandle, SearchResults
{

    // FIXME: put namespaces on constructed elements?

    static final private Logger logger = LoggerFactory.getLogger(DOMHandle.class);
    static private DocumentBuilderFactory bfactory = DocumentBuilderFactory.newInstance();
    static private SAXParserFactory pfactory = SAXParserFactory.newInstance();

    private QueryDefinition querydef = null;
    private SearchResponse searchResponse = null;
    private SearchMetrics metrics = null;
    private MatchDocumentSummary[] summary = null;
    private FacetResult[] facets = null;
    private String[] facetNames = null;
    private long totalResults = -1;
    private boolean alwaysDomSnippets = false;
    private Document metadata = null;

    public SearchHandle() {
    	super();
    	super.setFormat(Format.XML);
        pfactory.setValidating(false);
        pfactory.setNamespaceAware(true);
    }

    @Override
    public void setFormat(Format format) {
        if (format != Format.XML)
            new IllegalArgumentException("SearchHandle supports the XML format only");
    }

	public SearchHandle withFormat(Format format) {
		setFormat(format);
		return this;
	}

    public void setForceDOM(boolean forceDOM) {
        alwaysDomSnippets = forceDOM;
    }

	@Override
	protected Class<InputStream> receiveAs() {
        return InputStream.class;
    }

	@Override
	protected void receiveContent(InputStream content) {
        try {
            searchResponse = new SearchResponse();
            SAXParser parser = pfactory.newSAXParser();
            XMLReader reader = parser.getXMLReader();
            reader.setContentHandler(searchResponse);
            reader.parse(new InputSource(content));
        } catch (SAXException se) {
            throw new MarkLogicIOException("Could not construct search results: parser error", se);
        } catch (ParserConfigurationException pce) {
            throw new MarkLogicIOException("Could not construct search results: parser configuration error", pce);
        } catch (IOException ioe) {
            throw new MarkLogicIOException("Could not construct search results: I/O error", ioe);
        }
    }

    /** Sets the query definition used in the search.
     *
     * Calling this method always deletes any cached search results.
     *
     * @param querydef The new QueryDefinition
     */
    public void setQueryCriteria(QueryDefinition querydef) {
        this.querydef = querydef;
        metrics = null;
        summary = null;
        facets = null;
        facetNames = null;
    }
    
    @Override
    public QueryDefinition getQueryCriteria() {
        return querydef;
    }

    @Override
    public long getTotalResults() {
        return totalResults;
    }

    @Override
    public SearchMetrics getMetrics() {
        return metrics;
    }

    @Override
    public MatchDocumentSummary[] getMatchResults() {
        return summary;
    }

    @Override
    public Document getMetadata() {
        return metadata;
    }

    @Override
    public FacetResult[] getFacetResults() {
        return facets;
    }

    @Override
    public FacetResult getFacetResult(String name) {
        if (facets != null) {
            for (FacetResult facet : facets) {
                if (facet.getName().equals(name)) {
                    return facet;
                }
            }
        }
        return null;
    }

    @Override
    public String[] getFacetNames() {
        return facetNames;
    }

    private class SearchMetricsImpl implements SearchMetrics {
        long qrTime = -1;
        long frTime = -1;
        long srTime = -1;
        long mrTime = -1;
        long totalTime = -1;

        public SearchMetricsImpl(long qrTime, long frTime, long srTime, long mrTime, long totalTime) {
            this.qrTime = qrTime;
            this.frTime = frTime;
            this.srTime = srTime;
            this.mrTime = mrTime;
            this.totalTime = totalTime;
        }

        @Override
        public long getQueryResolutionTime() {
            return qrTime;
        }

        @Override
        public long getFacetResolutionTime() {
            return frTime;
        }

        @Override
        public long getSnippetResolutionTime() {
            return srTime;
        }

        @Override
        public long getMetadataResolutionTime() {
            return mrTime;
        }

        @Override
        public long getTotalTime() {
            return totalTime;
        }
    }

    private class MatchDocumentSummaryImpl implements MatchDocumentSummary {
        private String uri = null;
        private int score = -1;
        private double conf = -1;
        private double fit = -1;
        private String path = null;
        private ArrayList<MatchLocation> locvec = new ArrayList<MatchLocation>();
        private MatchLocation[] locations = null;
        private ArrayList<Document> snippets = new ArrayList<Document>();
        private String mimetype = null;
        private long byteLength = 0;

        public MatchDocumentSummaryImpl(String uri, int score, double confidence, double fitness, String path) {
            this.uri = uri;
            this.score = score;
            conf = confidence;
            fit = fitness;
            this.path = path;
        }

        @Override
        public String getUri() {
            return uri;
        }

        @Override
        public int getScore() {
            return score;
        }

        @Override
        public double getConfidence() {
            return conf;
        }

        @Override
        public double getFitness() {
            return fit;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public Document[] getSnippets() {
            return snippets.toArray(new Document[0]);
        }

        @Override
        public MatchLocation[] getMatchLocations() {
            if (locations == null) {
                locations = locvec.toArray(new MatchLocation[0]);
                locvec.clear();
            }

            return locations;
        }

        public void addLocation(MatchLocation loc) {
            locvec.add(loc);
        }

        public MatchLocation getCurrentLocation() {
            return locvec.get(locvec.size()-1);
        }

        public void addSnippet(Document snippet) {
            this.snippets.add(snippet);
        }
    }

    public class MatchLocationImpl implements MatchLocation {
        private String path = null;
        private ArrayList<MatchSnippet> snips = new ArrayList<MatchSnippet>();
        private MatchSnippet[] snippets = null;

        public MatchLocationImpl(String path) {
            this.path = path;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getAllSnippetText() {
            getSnippets();
            String text = "";
            if (snippets != null) {
                for (MatchSnippet snippet : snippets) {
                    text += snippet.getText();
                }
            }
            return text;
        }

        @Override
        public MatchSnippet[] getSnippets() {
            if (snippets == null) {
                snippets = snips.toArray(new MatchSnippet[0]);
                snips.clear();
            }

            return snippets;
        }

        public void addSnippet(MatchSnippet s) {
            snips.add(s);
        }
    }

    private class MatchSnippetImpl implements MatchSnippet {
        private boolean high = false;
        private String text = null;
        private Document dom = null;

        public MatchSnippetImpl(boolean high, String text) {
            this.high = high;
            this.text = text;
        }

        @Override
        public boolean isHighlighted() {
            return high;
        }

        @Override
        public String getText() {
            return text;
        }
    }

    public class FacetResultImpl implements FacetResult {
        private String name = null;
        private FacetValue[] values = null;

        public FacetResultImpl(String name, FacetValue[] values) {
            this.name = name;
            this.values = values;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public FacetValue[] getFacetValues() {
            return values;
        }
    }

    public class FacetValueImpl implements FacetValue {
        private String name = null;
        private long count = 0;
        private String label = null;

        public FacetValueImpl(String name, long count) {
            this.name = name;
            this.count = count;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getCount() {
            return count;
        }

        @Override
        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    public class FacetHeatmapValueImpl implements FacetHeatmapValue {
        private String name = null;
        private long count = 0;
        private String label = null;
        private double[] box = null;

        public FacetHeatmapValueImpl(String name, long count, double s, double w, double n, double e) {
            box = new double[4];
            box[0] = s;
            box[1] = w;
            box[2] = n;
            box[3] = e;

            name = "[" + box[0] + ", " + box[1]+ ", " + box[2] + ", " + box[3] + "]";
            this.count = count;
            label = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getCount() {
            return count;
        }

        @Override
        public String getLabel() {
            return label;
        }

        @Override
        public double[] getBox() {
            return box;
        }
    }

    private class SearchResponse implements ContentHandler {
        private boolean parsing = false;
        private HashMap<String, String> nsmap = new HashMap<String, String> ();
        private boolean buildDOM = false;
        private boolean inMatch = false;
        private boolean inHighlight = false;
        private boolean inMetrics = false;
        private boolean inTime = false;
        private Stack<Node> stack = null;
        private ArrayList<WSorChar> preceding = null;
        private DocumentBuilder builder = null;
        private DOMImplementation domImpl = null;
        private DatatypeFactory dtFactory = null;
        private Calendar now = Calendar.getInstance();
        String characters = null;

        String facetName = null;
        private ArrayList<FacetValue> facetValues = null;
        private ArrayList<String> facetNamesList = null;
        private ArrayList<FacetResult> facetResults = null;

        long start = 0;
        int pageLength = 0;
        Document dom = null;
        String snippetFormat = null;
        MatchDocumentSummary[] matchSummaries = null;
        int matchSlot = 0;

        long qrTime = -1;
        long frTime = -1;
        long srTime = -1;
        long tTime  = -1;
        long mrTime = -1;

        public SearchResponse() {
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            // nop
        }

        @Override
        public void startDocument() throws SAXException {
            parsing = true;
        }

        @Override
        public void endDocument() throws SAXException {
            parsing = false;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            nsmap.put(prefix, uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            nsmap.remove(prefix);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (buildDOM) {
                handleDOM(uri, localName,  qName, attributes);
                return;
            }

            if (!"http://marklogic.com/appservices/search".equals(uri)) {
                return;
            }

            if ("response".equals(localName))           { handleResponse(uri, localName, attributes);
            } else if ("result".equals(localName))      { handleResult(uri, localName, attributes);
            } else if ("snippet".equals(localName))     { handleSnippet();
            } else if ("meta".equals(localName))        { handleMetadata();
            } else if ("match".equals(localName))       { handleMatch(uri, localName, attributes);
            } else if ("highlight".equals(localName))   { inHighlight = true;
            } else if ("facet".equals(localName))       { handleFacet(attributes);
            } else if ("facet-value".equals(localName)) { handleFacetValue(attributes);
            } else if ("boxes".equals(localName))       { handleGeoFacet(attributes);
            } else if ("box".equals(localName))         { handleGeoFacetValue(attributes);
            } else if ("qtext".equals(localName))       { // nop
            } else if ("query".equals(localName))       { // nop
            } else if ("report".equals(localName))       { // nop
            } else if ("metrics".equals(localName))     { handleMetrics();
            } else if ("query-resolution-time".equals(localName) || "facet-resolution-time".equals(localName)
                        || "snippet-resolution-time".equals(localName) || "total-time".equals(localName)
                        || "metadata-resolution-time".equals(localName)) {
                inTime = inMetrics;
            } else {
                throw new UnsupportedOperationException("Unexpected element in search results: " + localName);
            }

            characters = null;
        }

        private void handleResponse(String uri, String localName, Attributes attributes) {
            snippetFormat = attributes.getValue("", "snippet-format");

            if (alwaysDomSnippets || !"snippet".equals(snippetFormat)) {
                try {
                    builder = bfactory.newDocumentBuilder();
                    domImpl = builder.getDOMImplementation();
                    stack = new Stack<Node>();
                    preceding = new ArrayList<WSorChar>();
                } catch (ParserConfigurationException pce) {
                    throw new MarkLogicIOException("Failed to create document builder", pce);
                }
            }

            String v = attributes.getValue("", "total");
            totalResults = Long.parseLong(v);
            v = attributes.getValue("", "page-length");
            pageLength = Integer.parseInt(v);
            v = attributes.getValue("", "start");
            start = Long.parseLong(v);
            matchSummaries = new MatchDocumentSummary[pageLength];
            matchSlot = 0;
        }

        private void handleResult(String uri, String localName, Attributes attributes) {
            String ruri = attributes.getValue("", "uri");
            String path = attributes.getValue("", "path");
            int score = Integer.parseInt(attributes.getValue("", "score"));
            double confidence = Double.parseDouble(attributes.getValue("", "confidence"));
            double fitness = Double.parseDouble(attributes.getValue("", "fitness"));

            matchSummaries[matchSlot] = new MatchDocumentSummaryImpl(ruri, score, confidence, fitness, path);

            if (alwaysDomSnippets || !"snippet".equals(snippetFormat)) {
                buildDOM = true;
                stack.clear();
            }
        }

        private void handleSnippet() {
            if (alwaysDomSnippets || !"snippet".equals(snippetFormat)) {
                buildDOM = true;
                stack.clear();
            }
        }

        private void handleMetadata() {
            buildDOM = true;

            if (stack == null) {
                try {
                    builder = bfactory.newDocumentBuilder();
                    domImpl = builder.getDOMImplementation();
                    stack = new Stack<Node>();
                    preceding = new ArrayList<WSorChar>();
                } catch (ParserConfigurationException pce) {
                    throw new MarkLogicIOException("Failed to create document builder", pce);
                }
            } else {
                stack.clear();
            }

            // Make sure there's a wrapper
            handleDOM("http://marklogic.com/appservices/search", "metadata", "search:metadata", null);
        }

        /* This is a convenience for debugging, leave it hear to save myself from having to rewrite it
        private void dumpDOM(Document dom) {
            DOMImplementationLS domImpl = (DOMImplementationLS) builder.getDOMImplementation();
            LSOutput domOutput = domImpl.createLSOutput();
            domOutput.setByteStream(System.err);
            domImpl.createLSSerializer().write(dom, domOutput);
        }
        */

        private void handleMatch(String uri, String localName, Attributes attributes) {
            MatchLocation loc = new MatchLocationImpl(attributes.getValue("", "path"));
            ((MatchDocumentSummaryImpl) matchSummaries[matchSlot]).addLocation(loc);
            inMatch = true;
        }

        private void handleFacet(Attributes attributes) {
            facetName = attributes.getValue("", "name");
            if (facetValues == null) {
                facetValues = new ArrayList<FacetValue>();
                facetResults = new ArrayList<FacetResult>();
                facetNamesList = new ArrayList<String>();
                facetNamesList.add(facetName);
            }
            facetValues.clear();
        }

        private void handleFacetValue(Attributes attributes) {
            String name = attributes.getValue("", "name");
            long count = Long.parseLong(attributes.getValue("","count"));
            facetValues.add(new FacetValueImpl(name, count));
        }

        private void handleGeoFacet(Attributes attributes) {
            facetName = attributes.getValue("", "name");
            if (facetValues == null) {
                facetValues = new ArrayList<FacetValue>();
                facetResults = new ArrayList<FacetResult>();
                facetNamesList = new ArrayList<String>();
                facetNamesList.add(facetName);
            }
            facetValues.clear();
        }

        private void handleGeoFacetValue(Attributes attributes) {
            String name = attributes.getValue("", "name");
            long count = Long.parseLong(attributes.getValue("", "count"));
            double s = Double.parseDouble(attributes.getValue("", "s"));
            double w = Double.parseDouble(attributes.getValue("", "w"));
            double n = Double.parseDouble(attributes.getValue("", "n"));
            double e = Double.parseDouble(attributes.getValue("", "e"));
            facetValues.add(new FacetHeatmapValueImpl(name, count, s, w, n, e));
        }

        private void handleMetrics() {
            inMetrics = true;
            if (dtFactory == null) {
                try {
                    dtFactory = DatatypeFactory.newInstance();
                } catch (DatatypeConfigurationException dce) {
                    throw new MarkLogicIOException("Cannot instantiate datatypeFactory", dce);
                }
            }
        }

        private void handleDOM(String uri, String localName, String qName, Attributes attributes) {
            handleCharacters();

            if (stack.isEmpty()) {
                dom = domImpl.createDocument(uri, qName, null);
                Element root = dom.getDocumentElement();
                setNamespaces(root, uri);

                if (attributes != null) {
                    for (int pos = 0; pos < attributes.getLength(); pos++) {
                        Attr attr = dom.createAttributeNS(attributes.getURI(pos), attributes.getQName(pos));
                        attr.setValue(attributes.getValue(pos));
                    }
                }

                /* TODO: This doesn't seem to be possible in the DOM...
                Node top = root;
                Node n = null;
                for (WSorChar ws : preceding) {
                    if (ws.isChars()) {
                        n = dom.createTextNode(new String(ws.chars));
                    } else {
                        n = dom.createProcessingInstruction(ws.target, ws.data);
                    }
                    dom.insertBefore(n, top);
                    top = n;
                }
                */

                preceding.clear();
                stack.push(root);
            } else {
                Element child = dom.createElementNS(uri, qName);
                setNamespaces((Element) stack.peek(), uri);
                for (int pos = 0; pos < attributes.getLength(); pos++) {
                    Attr attr = dom.createAttributeNS(attributes.getURI(pos), attributes.getQName(pos));
                    attr.setValue(attributes.getValue(pos));
                }
                stack.peek().appendChild(child);
                stack.push(child);
            }
        }

        private void setNamespaces(Element element, String uri) {
            for (String pfx : nsmap.keySet()) {
                String nsuri = nsmap.get(pfx);
                if (!nsuri.equals("")) {
                    String attr = "xmlns";
                    if (!"".equals(pfx)) {
                        attr += ":" + pfx;
                    }
                    if (!inScope(element, attr, nsuri)) {
                        element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attr, nsuri);
                    }
                }
            }
        }

        private boolean inScope(Element element, String attr, String uri) {
            Attr attrNode = element.getAttributeNodeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attr);
            if (attrNode != null && uri.equals(attrNode.getValue())) {
                return true;
            } else {
                Node parent = element.getParentNode();
                if (parent != null && parent.getNodeType() == Element.ELEMENT_NODE) {
                    return inScope((Element) parent, attr, uri);
                } else {
                    return false;
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (buildDOM) {
                handleCharacters();
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            }

            if (!"http://marklogic.com/appservices/search".equals(uri)) {
                characters = null;
                return;
            }

            if ("snippet".equals(localName)) {
                if (buildDOM) {
                    buildDOM = false;
                    ((MatchDocumentSummaryImpl) matchSummaries[matchSlot]).addSnippet(dom);
                }
                characters = null;
                return;
            }

            if ("meta".equals(localName)) {
                metadata = dom;
                characters = null;
                return;
            }

            if ("match".equals(localName)) {
                if (!buildDOM) {
                    if (characters != null) {
                        MatchSnippet snippet = new MatchSnippetImpl(false, characters);
                        MatchLocation location = ((MatchDocumentSummaryImpl) matchSummaries[matchSlot]).getCurrentLocation();
                        ((MatchLocationImpl) location).addSnippet(snippet);
                    }
                }
                inMatch = false;
                characters = null;
                return;
            }

            if ("highlight".equals(localName)) {
                if (!buildDOM) {
                    if (characters != null) {
                        MatchSnippet snippet = new MatchSnippetImpl(true, characters);
                        MatchLocation location = ((MatchDocumentSummaryImpl) matchSummaries[matchSlot]).getCurrentLocation();
                        ((MatchLocationImpl) location).addSnippet(snippet);
                    }
                }
                inHighlight = false;
                characters = null;
                return;
            }

            if ("facet-value".equals(localName)) {
                ((FacetValueImpl) facetValues.get(facetValues.size()-1)).setLabel(characters);
                characters = null;
                return;
            }

            if ("box".equals(localName)) {
                characters = null;
                return;
            }

            if ("facet".equals(localName) || "boxes".equals(localName)) {
                facetResults.add(new FacetResultImpl(facetName, facetValues.toArray(new FacetValue[0])));
            }

            if ("metrics".equals(localName)) {
                inMetrics = false;
                metrics = new SearchMetricsImpl(qrTime, frTime, srTime, mrTime, tTime);
                characters = null;
                return;
            }

            if ("result".equals(localName)) {
                if (buildDOM && (alwaysDomSnippets || !"snippet".equals(snippetFormat))) {
                    buildDOM = false;
                    ((MatchDocumentSummaryImpl) matchSummaries[matchSlot]).addSnippet(dom);
                }

                matchSlot++;
                characters = null;

                return;
            }

            if (inTime) {
                if ("query-resolution-time".equals(localName))    { qrTime = parseTime(characters); }
                if ("facet-resolution-time".equals(localName))    { frTime = parseTime(characters); }
                if ("snippet-resolution-time".equals(localName))  { srTime = parseTime(characters); }
                if ("metadata-resolution-time".equals(localName)) { mrTime = parseTime(characters); }
                if ("total-time".equals(localName))               { tTime = parseTime(characters); }
                inTime = false;
                characters = null;
                return;
            }

            if ("response".equals(localName)) {
                if (matchSlot < matchSummaries.length) {
                    summary = new MatchDocumentSummary[matchSlot];
                    for (int pos = 0; pos < matchSlot; pos++) {
                        summary[pos] = matchSummaries[pos];
                    }
                } else {
                    summary = matchSummaries;
                }

                if (facetResults != null) {
                    facets = new FacetResult[facetResults.size()];
                    int pos = 0;
                    for (FacetResult v : facetResults) {
                        facets[pos++] = v;
                    }

                    facetNames = new String[facetNamesList.size()];
                    pos = 0;
                    for (String n : facetNamesList) {
                        facetNames[pos++] = n;
                    }
                }

                characters = null;
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) throws SAXException {
            // Handle the fact that start may be > 0 and length maybe < chars.size
            char[] ch = new char[length];
            for (int pos = 0; pos < length; pos++) {
                ch[pos] = chars[start+pos];
            }

            // Accumulate characters in case SAX breaks a string into more than one run...
            if (characters == null) {
                characters = new String(ch);
            } else {
                characters += new String(ch);
            }
        }

        @Override
        public void ignorableWhitespace(char[] chars, int start, int length) throws SAXException {
            characters(chars, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            if (buildDOM) {
                handleCharacters();
                if (stack.isEmpty()) {
                    preceding.add(new WSorChar(target, data));
                } else {
                    stack.peek().appendChild(dom.createProcessingInstruction(target, data));
                }
            }
        }

        @Override
        public void skippedEntity(String s) throws SAXException {
            // nop
        }

        private void handleCharacters() {
            if (characters != null) {
                if (stack.isEmpty()) {
                    preceding.add(new WSorChar(characters));
                } else {
                    stack.peek().appendChild(dom.createTextNode(characters));
                }
                characters = null;
            }
        }

        private long parseTime(String time) {
            Duration d = dtFactory.newDurationDayTime(time);
            return d.getTimeInMillis(now);
        }

        private class WSorChar {
            String chars = null;
            String target = null;
            String data = null;

            public WSorChar(String chars) {
                this.chars = chars;
            }

            public WSorChar(String target, String data) {
                this.target = target;
                this.data = data;
            }

            public boolean isChars() {
                return chars != null;
            }
        }
    }
}
