package fi.smaa.smaadd;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import fi.smaa.jsmaa.SMAA2Results;
import fi.smaa.jsmaa.SMAA2SimulationThread;
import fi.smaa.jsmaa.SMAASimulator;
import fi.smaa.jsmaa.model.Alternative;
import fi.smaa.jsmaa.model.CardinalCriterion;
import fi.smaa.jsmaa.model.Criterion;
import fi.smaa.jsmaa.model.ImpactMatrix;
import fi.smaa.jsmaa.model.Interval;
import fi.smaa.jsmaa.model.SMAAModel;
import fi.smaa.jsmaa.model.ScaleCriterion;

public class SMAADDMain {
	
	private static final String ALTFILE = "alternatives.xml";
	private static final String CRITFILE = "criteria.xml";
	private static final String IMPACTFILE = "performanceTable.xml";
	private static final int ITERATIONS = 10000;
	
	private List<Alternative> alts;
	private List<Criterion> crit;
	private ImpactMatrix impactMatrix;
	private SMAAModel model;
	private SMAA2Results results;

	public static void main(String[] args) {
		MyOptions opts = new MyOptions();
		CmdLineParser parser = new CmdLineParser(opts);
		try {
			parser.parseArgument(args);			
		} catch(CmdLineException e ) {
			System.err.println(e.getMessage());
			parser.printUsage(System.err);
			return;
		}
		try {
			SMAADDMain main = new SMAADDMain();
			main.loadInputFiles(opts.getInputDir());
			main.constructModel();
			main.compute();
			main.writeResults(opts.getOutputDir());
		} catch (SAXException e) {
			System.err.println("Invalid format in input file - " + e.getMessage());
		} catch (Exception e) {
			System.err.println("Unable to load input file: " + e.getMessage());
		}
	}

	private void writeResults(String outputDir) {
		
	}

	private void compute() {
		SMAASimulator simul = new SMAASimulator(model, new SMAA2SimulationThread(model, ITERATIONS));
		simul.restart();
		while (simul.isRunning()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		results = (SMAA2Results) simul.getResults();
	}

	private void constructModel() {
		model = new SMAAModel("smaa-2 model");
		model.setAlternatives(alts);
		model.setCriteria(crit);
		for (Criterion c : crit) {
			for (Alternative a : alts) {
				model.setMeasurement((CardinalCriterion) c, a, 
						impactMatrix.getMeasurement((CardinalCriterion) c, a));
			}
		}
	}

	private void loadInputFiles(String inputDir) throws ParserConfigurationException, SAXException, IOException, Exception {
		alts = loadAlternatives(inputDir);
		crit = loadCriteria(inputDir);
		impactMatrix = loadImpactMatrix(alts, crit, inputDir);
	}

	private static ImpactMatrix loadImpactMatrix(List<Alternative> alts,
			List<Criterion> crit, String inputDir) throws ParserConfigurationException, SAXException, IOException, Exception {
		File altFile = new File(inputDir + File.separator + IMPACTFILE);
		BufferedInputStream bufStream = new BufferedInputStream(new FileInputStream(altFile));

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document dom = db.parse(bufStream);
		
		NodeList list = dom.getElementsByTagName("alternativePerformances");
		
		ImpactMatrix mat = new ImpactMatrix(alts, crit);
		
		for(int i=0;i<list.getLength();i++){
			Element ele = (Element)list.item(i);
			String altName =
				ele.getElementsByTagName("alternativeID").item(0).getChildNodes().item(0).getNodeValue();
			Alternative alt = findAlternative(alts, altName);
			
			NodeList critNode = ele.getElementsByTagName("performance");
			for (int j=0;j<critNode.getLength();j++) {
				Element perfElem = (Element) critNode.item(j);
				String critName =
					perfElem.getElementsByTagName("criterionID").item(0).getChildNodes().item(0).getNodeValue();
				CardinalCriterion c = (CardinalCriterion) findCriterion(crit, critName);
				
				Element valueElem = (Element)perfElem.getElementsByTagName("value").item(0);
				
				NodeList ivalElems = valueElem.getElementsByTagName("interval");
				Interval ival = null;
				if (ivalElems.getLength() == 0) {
					// real
					String realElem = valueElem.getElementsByTagName("real").item(0).getChildNodes().item(0).getNodeValue();
					Double val = Double.parseDouble(realElem);
					ival = new Interval(val, val);
				} else {
					Element lbValueElem = (Element) valueElem.getElementsByTagName("lowerBound").item(0);
					String lbound = lbValueElem.getElementsByTagName("real").item(0).getChildNodes().item(0).getNodeValue();
					Double dlb = Double.parseDouble(lbound);
					
					Element ubValueElem = (Element) valueElem.getElementsByTagName("upperBound").item(0);
					String ubound = ubValueElem.getElementsByTagName("real").item(0).getChildNodes().item(0).getNodeValue();
					Double dub = Double.parseDouble(ubound);

					ival = new Interval(dlb, dub);
				}
				mat.setMeasurement(c, alt, ival);
			}
		}
		
		for (Alternative a : alts) {
			for (Criterion c : crit) {
				if (mat.getMeasurement((CardinalCriterion) c, a) == null) {
					throw new Exception("Missing measurement for alternative " + a + " on criterion " + c);
				}
			}
		}
		
		bufStream.close();
		return mat;
	}

	private static Criterion findCriterion(List<Criterion> crit, String critName) throws Exception {
		for (Criterion c :crit) {
			if (c.getName().equals(critName)) {
				return c;
			}
		}
		throw new Exception("Unknown criterion in impact matrix");
	}

	private static Alternative findAlternative(List<Alternative> alts, String altName) throws Exception {
		for (Alternative a : alts) {
			if (a.getName().equals(altName)) {
				return a;
			}
		}
		throw new Exception("Unknown alternative in impact matrix");
	}

	private static List<Alternative> loadAlternatives(String inputDir)
			throws FileNotFoundException, ParserConfigurationException,
			SAXException, IOException {
		File altFile = new File(inputDir + File.separator + ALTFILE);
		BufferedInputStream bufStream = new BufferedInputStream(new FileInputStream(altFile));

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document dom = db.parse(bufStream);
		
		NodeList list = dom.getElementsByTagName("alternative");
		List<Alternative> alts = new ArrayList<Alternative>();
		for(int i=0;i<list.getLength();i++){ 
			Node item = list.item(i);
			String altName = item.getTextContent();
			Alternative alt = new Alternative(altName);
			alts.add(alt);
		}
		bufStream.close();
		return alts;
	}
	
	private static List<Criterion> loadCriteria(String inputDir)
	throws FileNotFoundException, ParserConfigurationException,
	SAXException, IOException {
		File altFile = new File(inputDir + File.separator + CRITFILE);
		BufferedInputStream bufStream = new BufferedInputStream(new FileInputStream(altFile));

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document dom = db.parse(bufStream);

		NodeList list = dom.getElementsByTagName("criterion");
		List<Criterion> crits = new ArrayList<Criterion>();
		for(int i=0;i<list.getLength();i++){ 
			Node item = list.item(i);
			String critName = item.getAttributes().getNamedItem("id").getNodeValue();
			CardinalCriterion crit = new ScaleCriterion(critName, true);
			crits.add(crit);
		}
		bufStream.close();
		return crits;
	}	
}
