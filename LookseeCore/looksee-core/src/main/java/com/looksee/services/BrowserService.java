package com.looksee.services;

import com.google.cloud.storage.StorageException;
import com.looksee.models.form.ElementRuleExtractor;
import com.looksee.browser.helpers.BrowserConnectionHelper;
import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.Session;
import com.looksee.config.LookseeBrowsingProperties;
import com.looksee.exceptions.ServiceUnavailableException;
import com.looksee.gcp.CloudVisionUtils;
import com.looksee.gcp.GoogleCloudStorage;
import com.looksee.gcp.ImageSafeSearchAnnotation;
import com.looksee.browser.Browser;
import com.looksee.services.browser.ElementStateAdapter;
import com.looksee.services.browser.ImageElementStateAdapter;
import com.looksee.services.browser.PageStateAdapter;
import com.looksee.services.browser.RemoteWebElement;
import com.looksee.models.Domain;
import com.looksee.browser.utils.CssUtils;
import com.looksee.browser.utils.HtmlUtils;
import com.looksee.utils.ScreenshotUtils;
import com.looksee.models.ElementState;
import com.looksee.models.Form;
import com.looksee.models.ImageElementState;
import com.looksee.models.ImageFaceAnnotation;
import com.looksee.models.ImageLandmarkInfo;
import com.looksee.models.ImageSearchAnnotation;
import com.looksee.models.Label;
import com.looksee.models.Logo;
import com.looksee.models.Page;
import com.looksee.models.PageState;
import com.looksee.models.Template;
import com.looksee.browser.enums.BrowserEnvironment;
import com.looksee.browser.enums.BrowserType;
import com.looksee.models.enums.ElementClassification;
import com.looksee.models.enums.TemplateType;
import com.looksee.utils.BrowserUtils;
import com.looksee.utils.ElementStateUtils;
import com.looksee.utils.ImageUtils;
import cz.vutbr.web.css.RuleSet;
import io.github.resilience4j.retry.annotation.Retry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.xml.xpath.XPathExpressionException;
import lombok.NoArgsConstructor;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.InvalidSelectorException;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;
import us.codecraft.xsoup.Xsoup;

/**
 * A collection of methods for interacting with the {@link Browser} session object
 */
@NoArgsConstructor
@Service
@ConditionalOnClass(name = "com.google.cloud.storage.Storage")
@ConditionalOnBean(GoogleCloudStorage.class)
public class BrowserService {
	private static Logger log = LoggerFactory.getLogger(BrowserService.class);
	
	private static String[] valid_xpath_attributes = {"class", "id", "name", "title"};

	@Autowired
	private ElementStateService element_state_service;

	@Autowired
	private ElementService element_service;
	
	@Autowired
	private GoogleCloudStorage googleCloudStorage;
	
	@Autowired
	private PageService page_service;

	@Autowired
	private ElementRuleExtractor extractor;

	@Autowired
	private PageStateAdapter pageStateAdapter;

	// Optional: absent if a consumer opts out of BrowsingClientConfiguration.
	// Null here means "local mode" — guarded in getConnection/capturePage.
	@Autowired(required = false)
	private LookseeBrowsingProperties browsingProps;

	// Optional: present only when looksee.browsing.mode=remote.
	@Autowired(required = false)
	private BrowsingClient browsingClient;

	/**
	 * Retrieves a new browser connection
	 *
	 * @param browser the browser (must not be null)
	 * @param browser_env the browser environment (must not be null)
	 *
	 * @return new {@link Browser} instance
	 * @throws MalformedURLException if the url is malformed
	 * @throws IllegalArgumentException if browser is null
	 *
	 * precondition: browser != null
	 * precondition: browser_env != null
	 */
	public Browser getConnection(BrowserType browser, BrowserEnvironment browser_env) throws MalformedURLException {
		assert browser != null;
		assert browser_env != null;

		if (browsingProps == null
				|| browsingProps.getMode() == LookseeBrowsingProperties.Mode.LOCAL) {
			return BrowserConnectionHelper.getConnection(
					BrowserType.create(browser.toString()),
					BrowserEnvironment.create(browser_env.toString()));
		}

		// Remote mode: create a server-side session and wrap it in RemoteBrowser.
		Session session = browsingClient.createSession(browser, browser_env);
		return new com.looksee.services.browser.RemoteBrowser(
				browsingClient, session.getSessionId(), browser.toString());
	}

	/**
	 * One-shot page capture: navigate, fetch source, screenshot, close — all in
	 * a single high-level call. In local mode this runs the current
	 * open → navigate → adapt → close sequence. In remote mode it uses
	 * browser-service's {@code POST /v1/capture} in a single round-trip.
	 *
	 * <p>Consumers can call this instead of the open/close dance to get
	 * transparent local→remote migration via the
	 * {@code looksee.browsing.mode} flag.
	 */
	public PageState capturePage(URL url, BrowserType browser, long audit_record_id)
			throws WebDriverException, IOException {
		assert url != null;
		assert browser != null;

		if (browsingProps == null
				|| browsingProps.getMode() == LookseeBrowsingProperties.Mode.LOCAL) {
			Browser b = null;
			try {
				b = getConnection(browser, BrowserEnvironment.DISCOVERY);
				b.navigateTo(url.toString());
				return pageStateAdapter.toPageState(b, audit_record_id, url.toString());
			} finally {
				if (b != null) {
					b.close();
				}
			}
		}

		// Remote (phase 3b): explicit session lifecycle produces distinct
		// viewport and full-page screenshots — previously the one-shot
		// /capture path had to reuse the same bytes for both fields (noted in
		// 0.6.0 CHANGELOG as 3b work). Five round-trips instead of two; same
		// fidelity as local mode.
		com.looksee.browsing.generated.model.Session session =
				browsingClient.createSession(browser, BrowserEnvironment.DISCOVERY);
		String sessionId = session.getSessionId();
		try {
			browsingClient.navigate(sessionId, url.toString());
			String source = browsingClient.getSource(sessionId);
			byte[] viewportBytes = browsingClient.screenshot(sessionId,
					com.looksee.browsing.generated.model.ScreenshotStrategy.VIEWPORT);
			byte[] fullPageBytes = browsingClient.screenshot(sessionId,
					com.looksee.browsing.generated.model.ScreenshotStrategy.FULL_PAGE_SHUTTERBUG);
			String sourceOrEmpty = source == null ? "" : source;
			return pageStateAdapter.toPageState(
					viewportBytes, fullPageBytes, sourceOrEmpty,
					audit_record_id, url.toString(), browser);
		} finally {
			try {
				browsingClient.deleteSession(sessionId);
			} catch (com.looksee.browsing.client.BrowsingClientException e) {
				log.warn("capturePage: deleteSession({}) failed; swallowing", sessionId, e);
			}
		}
	}

	/**
 	 * Constructs an {@link Element} from a JSOUP {@link Element element}
 	 *
	 * @param xpath the xpath (must not be null or empty)
	 * @param attributes the attributes (must not be null)
	 * @param element the element (must not be null)
	 * @param classification the classification (must not be null)
	 * @param rendered_css_values the rendered css values (must not be null)
	 * @param screenshot_url the screenshot url
	 * @param css_selector the css selector
	 * @param element_size the element size
	 * @param element_location the element location
	 *
	 * @return {@link ElementState} based on {@link WebElement} and other params
	 * @throws IOException if an error occurs while reading the screenshot
	 * @throws MalformedURLException if the url is malformed
	 * @throws IllegalArgumentException if xpath is null or empty
	 * @throws IllegalArgumentException if attributes is null
	 * @throws IllegalArgumentException if element is null
	 * @throws IllegalArgumentException if classification is null
	 * @throws IllegalArgumentException if rendered_css_values is null
	 */
	public static ElementState buildElementState(
			String xpath,
			Map<String, String> attributes,
			Element element,
			ElementClassification classification,
			Map<String, String> rendered_css_values,
			String screenshot_url,
			String css_selector,
			Dimension element_size,
			Point element_location
	) throws IOException{
		return ElementStateAdapter.toElementState(
				xpath,
				attributes,
				element,
				classification,
				rendered_css_values,
				screenshot_url,
				css_selector,
				element_size,
				element_location);
	}

	/**
	 * Constructs and Image Element State
	 *
	 * @param xpath the xpath
	 * @param attributes the attributes
	 * @param element the element
	 * @param classification the classification
	 * @param rendered_css_values the rendered css values
	 * @param screenshot_url the screenshot url
	 * @param css_selector the css selector
	 * @param landmark_info_set the landmark info set
	 * @param faces the faces
	 * @param image_search_set the image search set
	 * @param logos the logos
	 * @param labels the labels
	 * @param element_size the element size
	 * @param element_location the element location
	 *
	 * @return {@link ElementState} based on {@link WebElement} and other params
	 * @throws IOException if an error occurs while building the element state
	 */
	public static ElementState buildImageElementState(
			String xpath,
			Map<String, String> attributes,
			Element element,
			ElementClassification classification,
			Map<String, String> rendered_css_values,
			String screenshot_url,
			String css_selector,
			Set<ImageLandmarkInfo> landmark_info_set,
			Set<ImageFaceAnnotation> faces,
			ImageSearchAnnotation image_search_set,
			Set<Logo> logos,
			Set<Label> labels,
			Dimension element_size,
			Point element_location
	) throws IOException{
		return ImageElementStateAdapter.toImageElementState(
				xpath,
				attributes,
				element,
				classification,
				rendered_css_values,
				screenshot_url,
				css_selector,
				landmark_info_set,
				faces,
				image_search_set,
				logos,
				labels,
				element_size,
				element_location);
	}
	
	/**
	 * Generalizes HTML source by removing comments along with script, link, style, and iframe tags.
	 * Also removes attributes. The goal of this method is to strip out any dynamic data that could cause problems
	 *
	 * @param src the html string to generalize (must not be null)
	 *
	 * @return the generalized html string
	 * @throws IllegalArgumentException if src is null
	 */
	public static String generalizeSrc(String src) {
		assert src != null;
		
		if(src.isEmpty()) {
			return "";
		}
		
		Document html_doc = Jsoup.parse(src);
		html_doc.select("script").remove();
		html_doc.select("link").remove();
		html_doc.select("style").remove();
		html_doc.select("iframe").remove();
		html_doc.select("#gdpr").remove();
		html_doc.select("#gdprModal").remove();
		
		//html_doc.attr("id","");
		for(Element element : html_doc.getAllElements()) {
			List<String> attToRemove = new ArrayList<>();
			for (Attribute a : element.attributes()) {
				/*
				if(element.tagName().contentEquals("img") && a.getKey().contentEquals("src")) {
					continue;
				}
				*/
		        // transfer it into a list -
		        // to be sure ALL data-attributes will be removed!!!
				attToRemove.add(a.getKey());
			}

			for(String att : attToRemove) {
				element.removeAttr(att);
			}
		}
		
		removeComments(html_doc);
		
		return html_doc.html().replace("\n", "")
							.replace("\t", "")
							.replace("  ","")
							.replace(" ", "")
							.replace("> <", "><");	}
	
	/**
	 * Removes HTML comments from html string
	 *
	 * @param html the html string to remove comments from (must not be null)
	 *
	 * @return html string without comments
	 * @throws IllegalArgumentException if html is null
	 */
	public static String removeComments(String html) {
		assert html != null;
		
		return Pattern.compile("<!--.*?-->").matcher(html).replaceAll("");
    }
	
	/**
	 * Removes HTML comments from a {@link Element}
	 *
	 * @param e the element
	 */
	public static void removeComments(Element e) {
		assert e != null;

		e.childNodes().stream()
			.filter(n -> n.nodeName().equals("#comment"))
			.collect(Collectors.toList())
			.forEach(n -> n.remove());
		e.children().forEach(elem -> removeComments(elem));
	}
	
	/**
	 * Navigates to a url, checks that the service is available, then removes drift
	 * 	chat client from page if it exists. Finally it builds a {@link PageState}
	 * 
	 *Constructs a page object that contains all child elements that are considered to be potentially expandable.
	 * @param url the {@link URL} (must not be null)
	 * @param browser the {@link Browser} (must not be null)
	 * @param isSecure true if the page is secure, false otherwise
	 * @param httpStatus the http status code
	 * @param audit_record_id the audit record id
	 *
	 * @return page {@linkplain PageState}
	 * @throws StorageException if an error occurs while saving the page state
	 * @throws IOException if an error occurs while building the page state
	 * @throws NullPointerException if an error occurs while building the page state
	 * @throws IllegalArgumentException if browser is null or url is null
	 */
	public PageState buildPageState( URL url,
									Browser browser,
									boolean isSecure,
									int httpStatus,
									long audit_record_id )
											throws WebDriverException,
													IOException,
													NullPointerException
	{
		return pageStateAdapter.toPageState(url, browser, isSecure, httpStatus, audit_record_id);
	}
	
	/**
	 * identify and collect data for elements within the Document Object Model
	 *
	 * @param domain_audit_id the domain audit id
	 * @param page_state the {@link PageState} (must not be null)
	 * @param xpaths the xpaths (must not be null)
	 * @param browser the {@link Browser} (must not be null)
	 *
	 * @return {@link List list} of {@link ElementState element states}
	 *
	 * @throws Exception if an error occurs while getting the element states
	 * @throws XPathExpressionException if an error occurs while evaluating the xpath
	 * @throws IllegalArgumentException if xpaths is null
	 * @throws IllegalArgumentException if browser is null
	 * @throws IllegalArgumentException if page_state is null
	 */
	public List<ElementState> getDomElementStates(
			PageState page_state,
			List<String> xpaths,
			Browser browser,
			long domain_audit_id
	) throws Exception {
		assert xpaths != null;
		assert browser != null;
		assert page_state != null;
		
		List<ElementState> visited_elements = new ArrayList<>();
		String body_src = extractBody(page_state.getSrc());
		BufferedImage full_page_screenshot = ImageIO.read(new URL(page_state.getFullPageScreenshotUrl()));

		Document html_doc = Jsoup.parse(body_src);
		String host = (new URL(browser.getCurrentUrl())).getHost();
		
		List<String> errored_xpaths = new ArrayList<>();
		//iterate over xpaths to build ElementStates without screenshots
		for(String xpath : xpaths) {
			WebElement web_element = browser.findElement(xpath);
			if(web_element == null) {
				continue;
			}
			try {
				Dimension element_size = web_element.getSize();
				Point element_location = web_element.getLocation();
				boolean is_displayed = web_element.isDisplayed();
				//check if element is visible in pane and if not then continue to next element xpath
				if( !is_displayed
						|| !hasWidthAndHeight(element_size)
						|| doesElementHaveNegativePosition(element_location)
						|| isStructureTag( extractTagFromXpath(xpath))
						|| BrowserUtils.isHidden(element_location, element_size)){
					continue;
				}
				
				//load json element
				Elements elements = Xsoup.compile(xpath).evaluate(html_doc).getElements();
				if(elements.size() == 0) {
					log.warn("NO ELEMENTS WITH XPATH FOUND :: "+xpath);
				}
								
				Element element = elements.first();
				String css_selector = generateCssSelectorFromXpath(xpath);
				ElementClassification classification = ElementClassification.UNKNOWN;
				if(isImageElement(extractTagFromXpath(xpath))) {
					ElementState element_state = buildImageElementState(xpath,
																		new HashMap<>(),
																		element,
																		classification,
																		new HashMap<>(),
																		null,
																		css_selector,
																		null,
																		null,
																		null,
																		null,
																		null,
																		element_size,
																		element_location);
					
					ElementState element_record = element_state_service.findByDomainAuditAndKey(domain_audit_id, element_state);
					if(element_record == null) {
						//element_state = enrichElementState(browser, web_element, element_state, full_page_screenshot, host);
						//element_state = enrichImageElement(element_state, page_state, browser, host);
						element_record = element_state_service.save(domain_audit_id, element_state);
					}
					
					visited_elements.add(element_record);
				}
				else {
					ElementState element_state = buildElementState(xpath,
																	new HashMap<>(),
																	element,
																	classification,
																	new HashMap<>(),
																	null,
																	css_selector,
																	element_size,
																	element_location);
					
					ElementState element_record = element_state_service.findByDomainAuditAndKey(domain_audit_id, element_state);
					if(element_record == null) {
						element_state = enrichElementState(browser, web_element, element_state, full_page_screenshot, host);
						element_state = ElementStateUtils.enrichBackgroundColor(element_state);
						element_record = element_state_service.save(domain_audit_id, element_state);
					}
					
					visited_elements.add(element_record);
				}
			}catch(Exception e) {
				errored_xpaths.add(xpath);
				e.printStackTrace();
			}
		}

		return visited_elements;
	}

	/** MESSAGE GENERATION METHODS **/
	static String[] data_extraction_messages = {
			"Locating elements",
			"Create an account to get results faster",
			"Looking for content",
			"Having a look-see",
			"Extracting colors",
			"Checking fonts",
			"Pssst. Get results faster by logging in",
			"Mapping page structure",
			"Locating links",
			"Extracting navigation",
			"Pssst. Get results faster by logging in",
			"Create an account to get results faster",
			"Mapping CSS styles",
			"Generating unique CSS selector",
			"Mapping forms",
			"Measuring whitespace",
			"Pssst. Get results faster by logging in",
			"Create an account to get results faster",
			"Mapping attributes",
			"Mapping attributes",
			"Mapping attributes",
			"Mapping attributes",
			"Mapping attributes",
			"Mapping attributes",
			"Extracting color palette",
			"Looking for headers",
			"Mapping content structure",
			"Create an account to get results faster",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Wow! There's a lot of elements here",
			"Crunching the numbers",
			"Pssst. Get results faster by logging in",
			"Create an account to get results faster",
			"Searching for areas of interest",
			"Evaluating purpose of webpage",
			"Just a single page audit? Login to audit a domain",
			"Labeling icons",
			"Labeling images",
			"Labeling logos",
			"Applying customizations",
			"Checking for overfancification",
			"Grouping by proximity",
			"Almost there!",
			"Create an account to get results faster",
			"Labeling text elements",
			"Labeling links",
			"Pssst. Get results faster by logging in",
			"Labeling images",
			"Mapping form fields",
			"Extracting templates",
			"Contemplating the meaning of the universe",
			"Checking template structure"
			};

	/**
	 * Removes all {@link Element}s that have a negative or 0 value for the x or y coordinates
	 *
	 * @param webElements the {@link List} of {@link ElementState}s or {@link WebElement}s to filter
	 * @param isElementState true if the elements are {@link ElementState}s, false if they are {@link WebElement}s
	 *
	 * @return filtered list of {@link Element}s
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>web_elements != null</li>
	 * </ul>
	 */
	public static List<ElementState> filterElementsWithNegativePositions(List<ElementState> webElements,
																		boolean isElementState)
	{
		assert(webElements != null);

		List<ElementState> elements = new ArrayList<>();

		for(ElementState element : webElements){
			if(element.getXLocation() >= 0 && element.getYLocation() >= 0){
				elements.add(element);
			}
		}

		return elements;
	}

	/**
	 * Filters out all {@link WebElement}s that are not displayed
	 *
	 * @param webElements the {@link List} of {@link WebElement}s to filter
	 *
	 * @return filtered list of {@link WebElement}s
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>web_elements != null</li>
	 * </ul>
	 */
	public static List<WebElement> filterNonDisplayedElements(List<WebElement> webElements) {
		assert webElements != null;
		
		List<WebElement> filtered_elems = new ArrayList<WebElement>();
		for(WebElement elem : webElements){
			if(elem.isDisplayed()){
				filtered_elems.add(elem);
			}
		}
		
		return filtered_elems;
	}

	/**
	 * Filters out all {@link WebElement}s that are children of other elements
	 *
	 * @param web_elements the {@link List} of {@link WebElement}s to filter
	 *
	 * @return filtered list of {@link WebElement}s
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>web_elements != null</li>
	 * </ul>
	 */
	public static List<WebElement> filterNonChildElements(List<WebElement> web_elements) {
		assert web_elements != null;

		List<WebElement> filtered_elems = new ArrayList<WebElement>();
		for(WebElement elem : web_elements){
			boolean is_child = getChildElements(elem).isEmpty();
			if(is_child){
				filtered_elems.add(elem);
			}
		}
		
		return filtered_elems;
	}

	/**
	 * Filters out all {@link WebElement}s that have a negative or 0 value for the x or y coordinates
	 *
	 * @param web_elements the {@link List} of {@link WebElement}s to filter
	 *
	 * @return filtered list of {@link WebElement}s
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>web_elements != null</li>
	 * </ul>
	 */
	public static List<WebElement> filterElementsWithNegativePositions(List<WebElement> web_elements) {
		assert web_elements != null;
		
		List<WebElement> elements = new ArrayList<>();

		for(WebElement element : web_elements){
			Point location = element.getLocation();
			if(location.getX() >= 0 && location.getY() >= 0){
				elements.add(element);
			}
		}

		return elements;
	}
	
	/**
	 * Checks if {@link Point location} has a negative x or y coordinate
	 *
	 * @param location the {@link Point} to check
	 *
	 * @return true if the point has a negative x or y coordinate, otherwise false
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>location != null</li>
	 * </ul>
	 */
	public static boolean doesElementHaveNegativePosition(Point location) {
		assert location != null;
		
		return location.getX() < 0 || location.getY() < 0;
	}

	/**
	 * Checks if {@link Dimension dimension} has a height and width greater than 1
	 *
	 * @param dimension the {@link Dimension} to check
	 *
	 * @return true if the dimension has a height and width greater than 1, otherwise false
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>dimension != null</li>
	 * </ul>
	 */
	public static boolean hasWidthAndHeight(Dimension dimension) {
		assert dimension != null;
		
		return dimension.getHeight() > 1 && dimension.getWidth() > 1;
	}

	/**
	 * Filters out html, body, link, title, script, meta, head, iframe, or noscript tags
	 *
	 * @param tagName the tag name to check
	 *
	 * @return true if tag name is html, body, link, title, script, meta, head, iframe, or noscript
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>tagName != null</li>
	 * </ul>
	 */
	public static boolean isStructureTag(String tagName) {
		assert tagName != null;

		return "head".contentEquals(tagName) || "link".contentEquals(tagName)
				|| "script".contentEquals(tagName) || "g".contentEquals(tagName)
				|| "path".contentEquals(tagName) || "svg".contentEquals(tagName)
				|| "polygon".contentEquals(tagName) || "br".contentEquals(tagName)
				|| "style".contentEquals(tagName) || "polyline".contentEquals(tagName)
				|| "use".contentEquals(tagName) || "template".contentEquals(tagName)
				|| "audio".contentEquals(tagName)  || "iframe".contentEquals(tagName)
				|| "noscript".contentEquals(tagName) || "meta".contentEquals(tagName)
				|| "base".contentEquals(tagName) || "em".contentEquals(tagName)
				|| "body".contentEquals(tagName);
	}

	/**
	 * Filters out all {@link WebElement}s that have a width and height less than 1
	 *
	 * @param webElements the {@link List} of {@link WebElement}s to filter
	 *
	 * @return filtered list of {@link WebElement}s
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>webElements != null</li>
	 * </ul>
	 */
	public static List<WebElement> filterNoWidthOrHeight(List<WebElement> webElements) {
		assert webElements != null;
		
		List<WebElement> elements = new ArrayList<WebElement>(webElements.size());
		for(WebElement element : webElements){
			Dimension dimension = element.getSize();
			if(dimension.getHeight() > 1 && dimension.getWidth() > 1){
				elements.add(element);
			}
		}

		return elements;
	}

	/**
	 * Filters out all {@link ElementState}s that have a width and height less than 1
	 *
	 * @param webElements the {@link List} of {@link ElementState}s to filter
	 * @param isElementState true if the elements are {@link ElementState}s, false if they are {@link WebElement}s
	 *
	 * @return filtered list of {@link ElementState}s
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>webElements != null</li>
	 * </ul>
	 */
	public static List<ElementState> filterNoWidthOrHeight(List<ElementState> webElements, boolean isElementState) {
		assert webElements != null;
		
		List<ElementState> elements = new ArrayList<>(webElements.size());
		for(ElementState element : webElements){
			if(element.getHeight() > 1 && element.getWidth() > 1){
				elements.add(element);
			}
		}

		return elements;
	}

	/**
	 * Checks if {@link WebElement element} is visible in the current viewport window or not
	 * 
	 * @param browser {@link Browser browser} connection to use
	 * @param location {@link Point point} where the element top left corner is located
	 * @param size {@link Dimension size} of the element
	 * 
	 * @return true if element is rendered within viewport, otherwise false
	 * 
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>browser != null</li>
	 *   <li>location != null</li>
	 *   <li>size != null</li>
	 * </ul>
	 */
	public static boolean isElementVisibleInPane(Browser browser, Point location, Dimension size){
		assert browser != null;
		assert location != null;
		assert size != null;

		//Point offsets = browser.getViewportScrollOffset();
		long y_offset = browser.getYScrollOffset();
		long x_offset = browser.getXScrollOffset();

		int x = location.getX();
		int y = location.getY();

		int height = size.getHeight();
		int width = size.getWidth();

		return x >= x_offset
				&& y >= y_offset
				&& ((x-x_offset)+width) <= (browser.getViewportSize().getWidth())
				&& ((y-y_offset)+height) <= (browser.getViewportSize().getHeight());
	}
	
	/**
	 * Checks if {@link ElementState element} is visible in the current viewport window or not
	 *
	 * @param browser {@link Browser browser} connection to use
	 * @param element {@link ElementState element} to be be evaluated
	 *
	 * @return true if element is rendered within viewport, otherwise false
	 *
	 * <b>Preconditions:</b>
	 * <ul>
	 *   <li>browser != null</li>
	 *   <li>element != null</li>
	 * </ul>
	 */
	public static boolean isElementVisibleInPane(Browser browser, ElementState element){
		assert browser != null;
		assert element != null;

		//Point offsets = browser.getViewportScrollOffset();
		long y_offset = browser.getYScrollOffset();
		long x_offset = browser.getXScrollOffset();

		int y = element.getYLocation();
		int x = element.getXLocation();

		int height = element.getHeight();
		int width = element.getWidth();

		return x >= x_offset
				&& y >= y_offset
				&& ((x-x_offset)+width) <= (browser.getViewportSize().getWidth())
				&& ((y-y_offset)+height) <= (browser.getViewportSize().getHeight());
	}

	/**
	 * Checks if {@link WebElement element} is visible in the current viewport window or not
	 * 
	 * @param browser {@link Browser browser} connection to use
	 * @param position {@link Point position} where the element top left corner is located
	 * @param size {@link Dimension size} of the element
	 * 
	 * @return true if element is rendered within viewport, otherwise false
	 *
	 * precondition: browser != null
	 * precondition: position != null
	 * precondition: size != null
	 */
	public static boolean doesElementFitInViewport(Browser browser,
													Point position,
													Dimension size)
	{
		assert browser != null;
		assert position != null;
		assert size != null;

		int height = size.getHeight();
		int width = size.getWidth();

		return width <= (browser.getViewportSize().getWidth())
				&& height <= (browser.getViewportSize().getHeight())
				&& position.getX() < browser.getViewportSize().getWidth()
				&& position.getX() >= 0
				&& position.getY() >= 0;
	}
	
	/**
	 * Get immediate child elements for a given element
	 *
	 * @param elem	WebElement to get children for
	 * @return list of WebElements
	 *
	 * precondition: elem != null
	 */
	public static List<WebElement> getChildElements(WebElement elem) throws WebDriverException{
		assert elem != null;

		return elem.findElements(By.xpath("./*"));
	}

	/**
	 * Get immediate child elements for a given element
	 *
	 * @param elem	WebElement to get children for
	 * @return list of WebElements
	 *
	 * precondition: elem != null
	 */
	public static List<WebElement> getNestedElements(WebElement elem) throws WebDriverException{
		assert elem != null;

		return elem.findElements(By.xpath(".//*"));
	}

	/**
	 * Get immediate parent elements for a given element
	 *
	 * @param elem	{@linkplain WebElement} to get parent of
	 * @return parent {@linkplain WebElement}
	 *
	 * precondition: elem != null
	 */
	public WebElement getParentElement(WebElement elem) throws WebDriverException{
		assert elem != null;

		return elem.findElement(By.xpath(".."));
	}

	/**
	 * Cleans attribute values
	 *
	 * @param attribute_values_string the attribute values string
	 * @return the cleaned attribute values string
	 *
	 * precondition: attribute_values_string != null
	 */
	public static String cleanAttributeValues(String attribute_values_string) {
		assert attribute_values_string != null;
		assert attribute_values_string != null;

		String escaped = attribute_values_string.replaceAll("[\\t\\n\\r]+"," ");
		escaped = escaped.trim().replaceAll("\\s+", " ");
		escaped = escaped.replace("\"", "\\\"");
		return escaped.replace("\'", "'");
	}

	/**
	 * generates a unique xpath for this element.
	 *
	 * @param element the {@link WebElement} to generate an xpath for (must not be null)
	 * @param driver the {@link WebDriver} to use
	 * @param attributes the {@link Map} of attributes to use
	 *
	 * @return an xpath that identifies this element uniquely
	 * @throws IllegalArgumentException if element is null
	 * @throws IllegalArgumentException if driver is null
	 * @throws IllegalArgumentException if attributes is null
	 */
	public String generateXpath(WebElement element,
								WebDriver driver,
								Map<String, String> attributes)
	{
		assert element != null;
		assert driver != null;
		assert attributes != null;
		
		List<String> attributeChecks = new ArrayList<>();
		List<String> valid_attributes = Arrays.asList(valid_xpath_attributes);
		
		String xpath = "/"+element.getTagName();
		for(String attr : attributes.keySet()){
			if(valid_attributes.contains(attr)){
				String attribute_values =attributes.get(attr);
				String trimmed_values = cleanAttributeValues(attribute_values.trim());

				if(trimmed_values.length() > 0 
						&& !BrowserUtils.isJavascript(trimmed_values)) {
					attributeChecks.add("contains(@" + attr + ",\"" + trimmed_values.split(" ")[0] + "\")");
				}
			}
		}
		if(attributeChecks.size()>0){
			xpath += "["+attributeChecks.get(0).toString() + "]";
		}

		WebElement parent = element;
		String parent_tag_name = parent.getTagName();
		while(!"html".equals(parent_tag_name) && !"body".equals(parent_tag_name)){
			try{
				parent = getParentElement(parent);
				if(driver.findElements(By.xpath("//"+parent.getTagName() + xpath)).size() == 1){
					return "//"+parent.getTagName() + xpath;
				}
				else{
					xpath = "/" + parent.getTagName() + xpath;
				}
			}catch(InvalidSelectorException e){
				parent = null;
				log.warn("Invalid selector exception occurred while generating xpath through parent nodes");
				break;
			}
		}
		xpath = "/"+xpath;
		return uniqifyXpath(element, xpath, driver);
	}

	/**
	 * Generates a unique xpath without reaching through the Browser abstraction.
	 * Remote elements already retain the xpath used to enumerate them, which is
	 * both unique and avoids a local-driver lookup.
	 */
	public String generateXpath(WebElement element,
								Browser browser,
								Map<String, String> attributes)
	{
		assert element != null;
		assert browser != null;
		assert attributes != null;

		String sourceXpath = getRemoteSourceXpath(element);
		if (sourceXpath != null) {
			return sourceXpath;
		}

		List<String> attributeChecks = new ArrayList<>();
		List<String> valid_attributes = Arrays.asList(valid_xpath_attributes);

		String xpath = "/"+element.getTagName();
		for(String attr : attributes.keySet()){
			if(valid_attributes.contains(attr)){
				String attribute_values =attributes.get(attr);
				String trimmed_values = cleanAttributeValues(attribute_values.trim());

				if(trimmed_values.length() > 0
						&& !BrowserUtils.isJavascript(trimmed_values)) {
					attributeChecks.add("contains(@" + attr + ",\"" + trimmed_values.split(" ")[0] + "\")");
				}
			}
		}
		if(attributeChecks.size()>0){
			xpath += "["+attributeChecks.get(0).toString() + "]";
		}

		WebElement parent = element;
		String parent_tag_name = parent.getTagName();
		while(!"html".equals(parent_tag_name) && !"body".equals(parent_tag_name)){
			try{
				parent = getParentElement(parent);
				if(browser.findElements("//"+parent.getTagName() + xpath).size() == 1){
					return "//"+parent.getTagName() + xpath;
				}
				else{
					xpath = "/" + parent.getTagName() + xpath;
				}
			}catch(InvalidSelectorException e){
				parent = null;
				log.warn("Invalid selector exception occurred while generating xpath through parent nodes");
				break;
			}
		}
		xpath = "/"+xpath;
		return uniqifyXpath(element, xpath, browser);
	}

	/**
	 * generates a unique xpath for this element.
	 *
	 * @param element the element to generate an xpath for (must not be null)
	 * @param doc the document to use (must not be null)
	 * @param attributes the attributes to use (must not be null)
	 * @param xpath_cnt the xpath count (must not be null)
	 *
	 * @return an xpath that identifies this element uniquely
	 * @throws IllegalArgumentException if element is null
	 * @throws IllegalArgumentException if doc is null
	 * @throws IllegalArgumentException if attributes is null
	 * @throws IllegalArgumentException if xpath_cnt is null
	 */
	@Deprecated
	public static String generateXpathUsingJsoup(Element element,
												Document doc,
												Attributes attributes,
												Map<String, Integer> xpath_cnt) {
		assert element != null;
		assert doc != null;
		assert attributes != null;
		assert xpath_cnt != null;
		List<String> attributeChecks = new ArrayList<>();
		List<String> valid_attributes = Arrays.asList(valid_xpath_attributes);
		Element element_copy = element.clone();
		String xpath = "/"+element.tagName();
		for(org.jsoup.nodes.Attribute attr : attributes.asList()){
			if(valid_attributes.contains(attr.getKey())){
				String attribute_values = attr.getValue();
				String trimmed_values = cleanAttributeValues(attribute_values.trim());
				//check if attribute is auto generated
				String reduced_values = removeAutoGeneratedValues(trimmed_values);
				if(reduced_values.length() > 0 && !reduced_values.contains("javascript") && !reduced_values.contains("void()")){
					attributeChecks.add("contains(@" + attr.getKey() + ",\"" + reduced_values.split(" ")[0] + "\")");
				}
			}
		}

		if(attributeChecks.size()>0){
			xpath += "["+ attributeChecks.get(0).toString()+"]";
		}

		Element last_element = element;
		Element parent = null;
		String last_element_tagname = last_element.tagName();
		while(!"html".equals(last_element_tagname) && !"body".equals(last_element_tagname)){
			try{
				parent = last_element.parent();

				if(!isStructureTag(parent.tagName())){
					Elements elements = Xsoup.compile("//"+parent.tagName() + xpath).evaluate(doc).getElements();
					if( elements.isEmpty()){
						break;
					}
					else if( elements.size() == 1){
						return "//"+parent.tagName() + xpath;
					}
					else{
						xpath = "/" + parent.tagName() + xpath;
					}
					last_element = parent;
					last_element_tagname = last_element.tagName();
				}
				else{
					log.warn("Encountered structure tag. Aborting element xpath extraction..");
					break;
				}
			}catch(InvalidSelectorException e){
				parent = null;
				log.warn("Invalid selector exception occurred while generating xpath through parent nodes");
				break;
			}
		}
		if(!xpath.startsWith("//")){
			xpath = "/"+xpath;
		}

		return uniqifyXpath(element_copy, xpath, doc, xpath_cnt);
	}
	
	/**
	 * generates a unique xpath for this element.
	 *
	 * @param xpath the xpath to generate a css selector for
	 * @return an xpath that identifies this element uniquely
	 *
	 * precondition: xpath != null
	 * precondition: !xpath.isEmpty()
	 */
	public static String generateCssSelectorFromXpath(String xpath){
		assert xpath != null;
		assert !xpath.isEmpty();

		List<String> selectors = new ArrayList<>();
		
		//split xpath on '/' character
		String[] xpath_selectors = xpath.split("/");
		for(String xpath_selector : xpath_selectors) {
			//transform selector to css selector
			String css_select = transformXpathSelectorToCss(xpath_selector);
			selectors.add(css_select);
		}
		
		return buildCssSelector(selectors);
	}

	/**
	 * combines list of sub selectors into cohesive css_selector
	 *
	 * @param selectors the list of selectors to combine
	 * @return the combined css selector
	 */
	private static String buildCssSelector(List<String> selectors) {
		String css_selector = "";
		
		for(String selector : selectors) {
			if(css_selector.isEmpty() && !selector.isEmpty()) {
				css_selector = selector;
			}
			else if(!css_selector.isEmpty() && !selector.isEmpty()){
				css_selector += " " + selector;
			}
		}
		
		return css_selector;
	}

	/**
	 * Transforms an xpath selector to a css selector
	 * @param xpath_selector the xpath selector to transform
	 *
	 * @return the css selector
	 */
	public static String transformXpathSelectorToCss(String xpath_selector) {
		assert xpath_selector != null;

		String selector = "";
		
		//convert index value with format '[integer]' to css format
		String pattern_string = "(\\[([0-9]+)\\])";
        Pattern pattern_index = Pattern.compile(pattern_string);
        Matcher matcher = pattern_index.matcher(xpath_selector);
        if(matcher.find()) {
			String match = matcher.group(1);
			match = match.replace("[", "");
			match = match.replace("]", "");
			int element_index = Integer.parseInt(match);
			selector = xpath_selector.replaceAll(pattern_string, "");

			selector += ":nth-child(" + element_index + ")";
        }
        else {
			selector = xpath_selector;
        }
        
		return selector.trim();
	}

	/**
	 * Removes auto-generated values from a string
	 * @param trimmed_values the string to remove auto-generated values from (must not be null)
	 * @return the string with auto-generated values removed
	 * @throws IllegalArgumentException if trimmed_values is null
	 */
	private static String removeAutoGeneratedValues(String trimmed_values) {
		String[] values = trimmed_values.split(" ");
		List<String> reduced_vals = new ArrayList<>();
		for(String val : values){
			//check if value is auto-generated
			if(!isAutoGenerated(val)){
				reduced_vals.add(val);
			}
		}
		return String.join(" ", reduced_vals);
	}

	/**
	 * Checks if a value is auto-generated
	 * @param val the value to check (must not be null)
	 * @return true if the value is auto-generated, otherwise false
	 * @throws IllegalArgumentException if val is null
	 */
	private static boolean isAutoGenerated(String val) {
		//check if value ends in a number
		return val.length() > 0 && Character.isDigit(val.charAt(val.length()-1));
	}

	/**
	 * generates a unique xpath for this element.
	 *
	 * @param element the element to generate the attributes map for
	 *
	 * @return the attributes map
	 *
	 * precondition: element != null
	 */
	public static Map<String, String> generateAttributesMapUsingJsoup(Element element){
		assert element != null;

		Map<String, String> attributes = new HashMap<>();
		for(Attribute attribute : element.attributes() ){
			attributes.put(attribute.getKey(), attribute.getValue());
		}

		return attributes;
	}

	/**
	 * creates a unique xpath based on a given hash of xpaths
	 *
	 * @param elem the {@link Element}
	 * @param xpath the xpath
	 * @param doc the {@link Document}
	 * @param xpath_cnt the xpath count
	 *
	 * @return the unique xpath
	 *
	 * precondition: elem != null
	 * precondition: xpath != null
	 * precondition: doc != null
	 * precondition: xpath_cnt != null
	 */
	public static String uniqifyXpath(Element elem, String xpath, Document doc, Map<String, Integer> xpath_cnt){
		assert elem != null;
		assert xpath != null;
		assert doc != null;
		assert xpath_cnt != null;

		try {
			List<Element> elements = Xsoup.compile(xpath).evaluate(doc).getElements();
			if(elements.size() > 1){
				int count = 0;
				if(xpath_cnt.containsKey(xpath)){
					count = xpath_cnt.get(xpath);
				}
				xpath_cnt.put(xpath, ++count);
				String unique_xpath = "("+xpath+")[" + count + "]";
				return unique_xpath;
			}

		}catch(InvalidSelectorException e){
			log.warn(e.getMessage());
		}

		return xpath;
	}

	/**
	 * creates a unique xpath based on a given hash of xpaths
	 *
	 * @param driver the driver
	 * @param xpath the xpath
	 * @param elem the {@link WebElement}
	 *
	 * @return the unique xpath
	 *
	 * precondition: elem != null
	 * precondition: xpath != null
	 * precondition: driver != null
	 */
	public static String uniqifyXpath(WebElement elem, String xpath, WebDriver driver){
		assert elem != null;
		assert xpath != null;
		assert driver != null;

		try {
			List<WebElement> elements = driver.findElements(By.xpath(xpath));
			String element_tag_name = elem.getTagName();

			if(elements.size()>1){
				int count = 1;
				for(WebElement element : elements){
					if(element.getTagName().equals(element_tag_name)
							&& element.getLocation().getX() == elem.getLocation().getX()
							&& element.getLocation().getY() == elem.getLocation().getY()){
						return "("+xpath+")[" + count + "]";
					}
					count++;
				}
			}

		}catch(InvalidSelectorException e){
			log.error(e.getMessage());
		}

		return xpath;
	}

	/**
	 * Creates a unique xpath through the {@link Browser} abstraction.
	 * Enumerated remote elements retain their indexed source xpath, so no
	 * additional remote lookup is needed.
	 */
	public static String uniqifyXpath(WebElement elem, String xpath, Browser browser){
		assert elem != null;
		assert xpath != null;
		assert browser != null;

		String sourceXpath = getRemoteSourceXpath(elem);
		if (sourceXpath != null) {
			return sourceXpath;
		}

		try {
			List<WebElement> elements = browser.findElements(xpath);
			String element_tag_name = elem.getTagName();

			if(elements.size()>1){
				int count = 1;
				for(WebElement element : elements){
					if(element.getTagName().equals(element_tag_name)
							&& element.getLocation().getX() == elem.getLocation().getX()
							&& element.getLocation().getY() == elem.getLocation().getY()){
						return "("+xpath+")[" + count + "]";
					}
					count++;
				}
			}

		}catch(InvalidSelectorException e){
			log.error(e.getMessage());
		}

		return xpath;
	}

	private static String getRemoteSourceXpath(WebElement element) {
		if (element instanceof RemoteWebElement) {
			return ((RemoteWebElement) element).getSourceXpath();
		}
		return null;
	}
	
	/**
	 * Finds templates in a list of elements
	 *
	 * @param element_list the list of elements to find templates in (must not be null)
	 * @return the map of templates
	 * @throws IllegalArgumentException if element_list is null
	 *
	 * precondition: element_list != null
	 */
	public Map<String, Template> findTemplates(List<com.looksee.models.Element> element_list){
		assert element_list != null;

		//create a map for the various duplicate elements
		Map<String, Template> element_templates = new HashMap<>();
		List<com.looksee.models.Element> parents_only_element_list = new ArrayList<>();
		for(com.looksee.models.Element element : element_list) {
			if(!ElementClassification.LEAF.equals(element.getClassification())) {
				parents_only_element_list.add(element);
			}
		}

		//iterate over all elements in list
		Map<String, Boolean> identified_templates = new HashMap<String, Boolean>();
		for(int idx1 = 0; idx1 < parents_only_element_list.size()-1; idx1++){
			com.looksee.models.Element element1 = parents_only_element_list.get(idx1);
			boolean at_least_one_match = false;
			if(identified_templates.containsKey(element1.getKey()) ) {
				continue;
			}
			//for each element iterate over all elements in list
			for(int idx2 = idx1+1; idx2 < parents_only_element_list.size(); idx2++){
				com.looksee.models.Element element2 = parents_only_element_list.get(idx2);
				if(identified_templates.containsKey(element2.getKey()) || !element1.getName().equals(element2.getName())){
					continue;
				}
				//get largest string length
				int max_length = element1.getTemplate().length();
				if(element2.getTemplate().length() > max_length){
					max_length = element2.getTemplate().length();
				}
				
				if(max_length == 0) {
					log.warn("max length of 0 between both templates");
					continue;
				}
				
				if(element1.getTemplate().equals(element2.getTemplate())){
					String template_str = element2.getTemplate();
					if(!element_templates.containsKey(template_str)){
						element_templates.put(template_str, new Template(TemplateType.UNKNOWN, template_str));
					}
					element_templates.get(template_str).getElements().add(element2);
					identified_templates.put(element2.getKey(), Boolean.TRUE);
					at_least_one_match = true;
					continue;
				}

				log.warn("getting levenshtein distance...");
				//double distance = StringUtils.getJaroWinklerDistance(element_list.get(idx1).getTemplate(), element_list.get(idx2).getTemplate());
				//calculate distance between loop1 value and loop2 value
				double distance = LevenshteinDistance.getDefaultInstance().apply(element1.getTemplate(), element2.getTemplate());

				//if value is within threshold then add loop2 value to map for loop1 value xpath
				double avg_string_size = ((element1.getTemplate().length() + element2.getTemplate().length())/2.0);
				double similarity = distance / avg_string_size;
				//double sigmoid = new Sigmoid(0,1).value(similarity);

				//calculate distance of children if within 20%
				if(distance == 0.0 || similarity < 0.025){
					log.warn("Distance ;  Similarity :: "+distance + "  ;  "+similarity);
					String template_str = element1.getTemplate();
					if(!element_templates.containsKey(template_str)){
						element_templates.put(template_str, new Template(TemplateType.UNKNOWN, template_str));
					}
					element_templates.get(template_str).getElements().add(element2);
					identified_templates.put(element2.getKey(), Boolean.TRUE);

					at_least_one_match = true;
				}
			}
			if(at_least_one_match){
				String template_str = element1.getTemplate();
				element_templates.get(template_str).getElements().add(element1);
				identified_templates.put(element1.getKey(), Boolean.TRUE);
			}
			log.warn("****************************************************************");

		}

		return element_templates;
	}

	/**
	 * Checks if Attributes contains keywords indicative of a slider
	 * @param attributes the attributes to check (must not be null or empty)
	 *
	 * @return true if any of keywords present, otherwise false
	 * @throws IllegalArgumentException if attributes is null or empty
	 */
	public static boolean doesAttributesContainSliderKeywords(Map<String, List<String>> attributes) {
		assert attributes != null;
		assert !attributes.isEmpty();
		for(String attr : attributes.keySet()) {
			if(attributes.get(attr).contains("slide")) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Extracts template for element by using outer html and removing inner text
	 * @param outerHtml the outer html of the element to extract template from
	 * @return templated version of element html
	 */
	public static String extractTemplate(String outerHtml){
		assert outerHtml != null;
		assert !outerHtml.isEmpty();
		
		Document html_doc = Jsoup.parseBodyFragment(outerHtml);

		Cleaner cleaner = new Cleaner(Safelist.relaxed());
		html_doc = cleaner.clean(html_doc);
		
		html_doc.select("script").remove()
				.select("link").remove()
				.select("style").remove();

		for(Element element : html_doc.getAllElements()) {
			element.removeAttr("id");
			element.removeAttr("name");
			element.removeAttr("style");
		}
		
		return html_doc.html();
	}
	
	/**
	 * Reduces a list of templates to a list of parent templates
	 * @param list_elements_list the list of templates to reduce (must not be null)
	 * @return the reduced list of templates
	 * @throws IllegalArgumentException if list_elements_list is null
	 *
	 * precondition: list_elements_list != null
	 */
	public Map<String, Template> reduceTemplatesToParents(Map<String, Template> list_elements_list) {
		assert list_elements_list != null;

		Map<String, Template> element_map = new HashMap<>();
		List<Template> template_list = new ArrayList<>(list_elements_list.values());
		//check if element is a child of another element in the list. if yes then don't add it to the list
		for(int idx1=0; idx1 < template_list.size(); idx1++){
			boolean is_child = false;
			for(int idx2=0; idx2 < template_list.size(); idx2++){
				if(idx1 != idx2 && template_list.get(idx2).getTemplate().contains(template_list.get(idx1).getTemplate())){
					is_child = true;
					break;
				}
			}

			if(!is_child){
				element_map.put(template_list.get(idx1).getTemplate(), template_list.get(idx1));
			}
		}

		//remove duplicates
		log.warn("total elements left after reduction :: " + element_map.values().size());
		return element_map;
	}

	/**
	 *
	 * Atom - A leaf element or an element that contains only 1 leaf element regardless of depth
	 * Molecule - Contains at least 2 atoms and cannot contain any molecules
	 * Organism - Contains at least 2 molecules or at least 1 molecule and 1 atom or at least 1 organism, Must not be an immediate child of body
	 * Template - An Immediate child of the body tag or the descendant such that the element is the first to have sibling elements
	 *
	 * @param template the template to classify
	 *
	 * @return the template type
	 *
	 * precondition: template != null
	 */
	public TemplateType classifyTemplate(String template){
		assert template != null;
		assert !template.isEmpty();

		Document html_doc = Jsoup.parseBodyFragment(template);
		Element root_element = html_doc.body();

		return classifyUsingChildren(root_element);
	}

	/**
	 * Classifies a template into an atom, molecule, organism, or template
	 *
	 * @param root_element the root element of the template
	 *
	 * @return the template type
	 *
	 * precondition: root_element != null
	 */
	private TemplateType classifyUsingChildren(Element root_element) {
		assert root_element != null;

		int atom_cnt = 0;
		int molecule_cnt = 0;
		int organism_cnt = 0;
		int template_cnt = 0;
		if(root_element.children() == null || root_element.children().isEmpty()){
			return TemplateType.ATOM;
		}

		//categorize each eleemnt
		for(Element element : root_element.children()){
			TemplateType type = classifyUsingChildren(element);
			if(type == TemplateType.ATOM){
				atom_cnt++;
			}
			else if(type == TemplateType.MOLECULE){
				molecule_cnt++;
			}
			else if(type == TemplateType.ORGANISM){
				organism_cnt++;
			}
			else if(type == TemplateType.TEMPLATE){
				template_cnt++;
			}
		}

		if(atom_cnt == 1){
			return TemplateType.ATOM;
		}
		else if(atom_cnt > 1 && molecule_cnt == 0 && organism_cnt == 0 && template_cnt == 0){
			return TemplateType.MOLECULE;
		}
		else if( (molecule_cnt == 1 && atom_cnt > 0 || molecule_cnt > 1 || organism_cnt > 0) && template_cnt == 0){
			return TemplateType.ORGANISM;
		}
		else if(isTopLevelElement(root_element)){
			return TemplateType.TEMPLATE;
		}
		return TemplateType.UNKNOWN;

	}

	/**
	 * Tests if the element is a top level element
	 *
	 * @param element the element to test
	 * @return true if the element is a top level element, false otherwise
	 *
	 * precondition:: element != null
	 */
	private boolean isTopLevelElement(Element element) {
		assert element != null;
		
		Element parent = element.parent();
		if(parent == null) {
			return true;
		}
		return false;
	}
	
	/**
	 * Tests if the list of keys contains the string "elementstate"
	 *
	 * @param keys the list of keys to test
	 * @return true if the list of keys contains the string "elementstate", false otherwise
	 *
	 * precondition: keys != null
	 */
	public static boolean testContainsElement(List<String> keys) {
		assert keys != null;
		assert keys != null;

		for(String key : keys) {
			if(key.contains("elementstate")) {
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Extracts all unique xpaths from a given source code
	 *
	 * @param src the source code to extract xpaths from
	 * @return a list of unique xpaths
	 *
	 * precondition: src != null
	 */
	public List<String> extractAllUniqueElementXpaths(String src) {
		assert src != null;
		
		Map<String, String> frontier = new HashMap<>();
		List<String> xpaths = new ArrayList<>();
		String body_src = extractBody(src);
		
		Document html_doc = Jsoup.parse(body_src);
		frontier.put("//body","");
		
		while(!frontier.isEmpty()) {
			String next_xpath = frontier.keySet().iterator().next();
			frontier.remove(next_xpath);
			xpaths.add(next_xpath);
			
			Elements elements = Xsoup.compile(next_xpath).evaluate(html_doc).getElements();
			if(elements.size() == 0) {
				log.warn("NO ELEMENTS WITH XPATH FOUND :: "+next_xpath);
				continue;
			}
			
			Element element = elements.first();
			List<Element> children = new ArrayList<Element>(element.children());
			Map<String, Integer> xpath_cnt = new HashMap<>();
			
			for(Element child : children) {
				if(isStructureTag(child.tagName())) {
					continue;
				}
				String xpath = next_xpath + "/" + child.tagName();
				
				if(xpath_cnt.containsKey(child.tagName()) ) {
					xpath_cnt.put(child.tagName(), xpath_cnt.get(child.tagName())+1);
				}
				else {
					xpath_cnt.put(child.tagName(), 1);
				}
				
				xpath = xpath + "["+xpath_cnt.get(child.tagName())+"]";

				frontier.put(xpath, "");
			}
		}
		
		xpaths = xpaths.parallelStream().map(xpath -> {
			return uniqifyXpath(xpath, html_doc);
		}).collect(Collectors.toList());
		
		return xpaths;
	}
	
	/**
	 * creates a unique xpath by taking a given xpath and shortening it from the left until it finds
	 * the shortest unique xpath.
	 *
	 * @param xpath a valid xpath for a {@link WebElement}
	 * @param driver the {@link WebDriver}
	 *
	 * @return a shortened unique xpath
	 */
	public static String uniqifyXpath(String xpath, WebDriver driver){
		assert xpath != null;
		assert driver != null;

		try {
			//parse xpath into array
			String temp_xpath = xpath.substring(1);
			String[] xpath_arr = temp_xpath.split("/");
			String last_unique_xpath = xpath;
			int last_index = 1;
			do {
				//construct new xpath by joining current array with '/'
				String new_xpath = "/";
				for(int i=last_index; i<xpath_arr.length; i++) {
					new_xpath += "/"+xpath_arr[i];
				}
				//get WebElements By xpath
				List<WebElement> web_elements = driver.findElements(By.xpath(new_xpath));
				
				if(web_elements.size() > 1) {
					break;
				}
				else {
					last_unique_xpath = new_xpath;
				}
				
				last_unique_xpath = new_xpath;
				last_index++;
			}while(last_index >= xpath_arr.length);
			return last_unique_xpath;
		}catch(InvalidSelectorException e){
			log.error(e.getMessage());
		}

		return xpath;
	}
	
	/**
	 * creates a unique xpath by taking a given xpath and shortening it from the left until it finds
	 * the shortest unique xpath.
	 *
	 * @param xpath a valid xpath for a {@link WebElement}
	 * @param html_doc a {@link Document}
	 *
	 * @return a shortened unique xpath
	 */
	public static String uniqifyXpath(String xpath, Document html_doc){
		assert xpath != null;
		assert html_doc != null;

		try {
			String temp_xpath = xpath.substring(1);
			String[] xpath_arr = temp_xpath.split("/");
			
			String last_unique_xpath = xpath;
			int last_index = 1;
			while(last_index <= xpath_arr.length) {
				//construct new xpath by joining current array with '/'
				String new_xpath = "/";
				for(int i=last_index; i<xpath_arr.length; i++) {
					new_xpath += "/"+xpath_arr[i];
				}

				Elements elements = Xsoup.compile(new_xpath).evaluate(html_doc).getElements();
				if(elements.size() > 1 || new_xpath.equals("/")) {
					break;
				}
				else {
					last_unique_xpath = new_xpath;
				}
				
				last_unique_xpath = new_xpath;
				last_index++;
			}
			
			return last_unique_xpath;
		}catch(InvalidSelectorException e){
			log.error(e.getMessage());
		}

		return xpath;
	}

	/**
	 * Extracts the body from a source code
	 * @param src the source code to extract the body from
	 * @return the body
	 *
	 * precondition:: src != null
	 */
	public static String extractBody(String src) {
		assert src != null;
		
		String patternString = "<body[^\\>]*>([\\s\\S]*)<\\/body>";

        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(src);
        if(matcher.find()) {
			return matcher.group();
        }
        return "";
	}

	/**
	 * Extracts the host from a URL string
	 * @param urlString The URL string to extract host from
	 * @return The host name without protocol or path
	 *
	 * precondition: urlString != null
	 * precondition: urlString is not empty
	 */
	public static String extractHost(String urlString) {
		assert urlString != null;
		assert !urlString.isEmpty();
		assert urlString != null;
		assert !urlString.isEmpty();

		try {
			URL url = new URL(urlString);
			return url.getHost();
		} catch (MalformedURLException e) {
			log.warn("Failed to extract host from URL: " + urlString);
			return "";
		}
	}

	/**
	 * Extracts metadata from a document
	 * @param html_doc the document to extract metadata from
	 * @return a set of metadata
	 *
	 * precondition: html_doc != null
	 */
	public static Set<String> extractMetadata(Document html_doc) {
		assert html_doc != null;
		assert html_doc != null;

		Elements meta_tags = html_doc.getElementsByTag("meta");
		Set<String> meta_tag_html = new HashSet<String>();
		
		for(Element meta_tag : meta_tags) {
			meta_tag_html.add(meta_tag.outerHtml());
		}
		return meta_tag_html;
	}

	/**
	 * Extracts stylesheets from a document
	 * @param html_doc the document to extract stylesheets from
	 * @return a set of stylesheet urls
	 *
	 * precondition: html_doc != null
	 */
	public static Set<String> extractStylesheets(Document html_doc) {
		assert html_doc != null;
		assert html_doc != null;

		Elements link_tags = html_doc.getElementsByTag("link");
		Set<String> stylesheet_urls = new HashSet<String>();
		
		for(Element link_tag : link_tags) {
			stylesheet_urls.add(link_tag.absUrl("href"));
		}
		return stylesheet_urls;
	}

	/**
	 * Extracts script urls from a document
	 * @param html_doc the document to extract script urls from
	 * @return a set of script urls
	 *
	 * precondition:: html_doc != null
	 */
	public static Set<String> extractScriptUrls(Document html_doc) {
		assert html_doc != null;
		
		Elements script_tags = html_doc.getElementsByTag("script");
		Set<String> script_urls = new HashSet<String>();
		
		for(Element script_tag : script_tags) {
			String src_url = script_tag.absUrl("src");
			if(src_url != null && !src_url.isEmpty()) {
				script_urls.add(script_tag.absUrl("src"));
			}
		}
		return script_urls;
	}

	/**
	 * Extracts icon links from a document
	 * @param html_doc the document to extract icon links from
	 * @return a set of icon urls
	 *
	 * precondition: html_doc != null
	 */
	public static Set<String> extractIconLinks(Document html_doc) {
		assert html_doc != null;
		assert html_doc != null;

		Elements icon_tags = html_doc.getElementsByTag("link");
		Set<String> icon_urls = new HashSet<String>();

		for(Element icon_tag : icon_tags) {
			if(icon_tag.attr("rel").contains("icon")){
				icon_urls.add(icon_tag.absUrl("href"));
			}
		}
		return icon_urls;
	}

	/**
	 * Enrich element states with ML applied labels
	 *
	 * @param element_states the list of element states
	 * @param page_state the page state
	 * @param browser the browser
	 * @param host the host
	 * @return the enriched element states
	 * @throws MalformedURLException if the url is malformed
	 * @throws IOException if an error occurs while extracting the screenshot
	 *
	 * precondition:: element_states != null
	 * precondition:: page_state != null
	 * precondition:: browser != null
	 * precondition:: host != null
	 */
	public List<ElementState> enrichElementStates(List<ElementState> element_states,
													PageState page_state,
													Browser browser,
													String host) throws MalformedURLException, IOException
	{
		assert element_states != null;
		assert page_state != null;
		assert browser != null;
		assert host != null;
		
		BufferedImage full_page_screenshot = ImageIO.read(new URL(page_state.getFullPageScreenshotUrl()));

		/*
		 * THE FOLLOWING BLOCK OF CODE IS FOR EXTRACTING ELEMENT SCREENSHOTS
		 */
		//order elements
		List<ElementState> ordered_elements = new ArrayList<>();
		ordered_elements = element_states.parallelStream()
												.sorted((o1, o2) -> Integer.compare(o1.getYLocation(), o2.getYLocation()))
												.collect(Collectors.toList());
		
		//extract screenshots for elements
		for(ElementState element: ordered_elements) {
			//Check if ElementState already exists for DomainAudit and if so, then check if a screenshot already exists.
			if(element.getYLocation() < browser.getYScrollOffset()) {
				browser.scrollToTopOfPage();
			}
			
			WebElement web_element = browser.findElement(element.getXpath());
			enrichElementState(browser, web_element, element, full_page_screenshot, host);
		}
		
		return ordered_elements;
	}
	
	
	/**
	 * Enrich an element state with screenshot, rendered css values, and attributes
	 * @param browser the browser
	 * @param web_element the web element
	 * @param element_state the element state
	 * @param page_screenshot the page screenshot
	 * @param host the host
	 * 
	 * @return the enriched element state
	 * @throws IOException if an error occurs while extracting the screenshot
	 *
	 * precondition:: browser != null
	 * precondition:: web_element != null
	 * precondition:: element_state != null
	 * precondition:: page_screenshot != null
	 * precondition:: host != null
	 */
	public ElementState enrichElementState(Browser browser,
											WebElement web_element,
											ElementState element_state,
											BufferedImage page_screenshot,
											String host) throws IOException
	{
		assert browser != null;
		assert web_element != null;
		assert element_state != null;
		assert page_screenshot != null;
		assert host != null;
		
		if(element_state.getYLocation() < browser.getYScrollOffset()) {
			browser.scrollToElement(web_element);
		}
		else {
			browser.scrollToElementCentered(web_element);
		}
		
		browser.waitForElementClickable(web_element, java.time.Duration.ofSeconds(10));
		
		//String current_url = browser.getDriver().getCurrentUrl();
		String element_screenshot_url = "";
		BufferedImage element_screenshot = null;
		Map<String, String> rendered_css_props = browser.getComputedCssProperties(web_element);
		Map<String, String> attributes = browser.extractAttributes(web_element);
		
		if(BrowserUtils.isLargerThanViewport(element_state, browser.getViewportSize().getWidth(), browser.getViewportSize().getHeight())) {
			try {
				element_screenshot = ScreenshotUtils.getElementScreenshot(element_state, page_screenshot);
				String screenshot_checksum = ImageUtils.getChecksum(element_screenshot);
				element_screenshot_url = googleCloudStorage.saveImage(element_screenshot,
																		host,
																		screenshot_checksum,
																		BrowserType.create(browser.getBrowserName()));
			}
			catch(Exception e1){
				log.warn("Exception occurred while extracting screenshot from full page screenshot");
			}
		}
		else {
			try {
				//extract element screenshot from full page screenshot
				element_screenshot = browser.getElementScreenshot(web_element);
				String screenshot_checksum = ImageUtils.getChecksum(element_screenshot);
				
				element_screenshot_url = googleCloudStorage.saveImage(element_screenshot,
																		host,
																		screenshot_checksum,
																		BrowserType.create(browser.getBrowserName()));
				element_screenshot.flush();
			}
			catch(Exception e1){}
		}

		element_state.setScreenshotUrl(element_screenshot_url);
		element_state.setRenderedCssValues(rendered_css_props);
		element_state.setAttributes(attributes);
		return element_state;
	}

	/**
	 * Enriches an image element with information about the image including
	 * logos, labels, faces, places, etc.
	 *
	 * @param element_state the element state to enrich
	 * @param page_state the page state
	 * @param browser the browser
	 * @param host the host
	 *
	 * @return the enriched element state or the original element state if it
	 * is not an image element
	 *
	 * precondition:: element_state != null
	 * precondition:: page_state != null
	 * precondition:: browser != null
	 * precondition:: host != null
	 * precondition:: element_state is an instance of ImageElementState
	 * precondition:: element_state.getScreenshotUrl() is not empty
	 */
	public ElementState enrichImageElement(ElementState element_state,
											PageState page_state,
											Browser browser,
											String host)
	{
		assert element_state != null;
		assert page_state != null;
		assert browser != null;
		assert host != null;
		assert element_state instanceof ImageElementState;
		assert !element_state.getScreenshotUrl().isEmpty();
		
		if(element_state instanceof ImageElementState && !element_state.getScreenshotUrl().isEmpty()) {
			BufferedImage element_screenshot;
			try {
				element_screenshot = ImageIO.read(new URL(element_state.getScreenshotUrl()));

				//retrieve image landmark properties from google cloud vision
				//Set<ImageLandmarkInfo> landmark_info_set = CloudVisionUtils.extractImageLandmarks(element_screenshot);
				Set<ImageLandmarkInfo> landmark_info_set = null;
				//retrieve image faces properties from google cloud vision
				//Set<ImageFaceAnnotation> faces = CloudVisionUtils.extractImageFaces(element_screenshot);
				Set<ImageFaceAnnotation> faces = null;
				//retrieve image reverse image search properties from google cloud vision
				ImageSearchAnnotation image_search_set = CloudVisionUtils.searchWebForImageUsage(element_screenshot);
				ImageSafeSearchAnnotation img_safe_search_annotation = CloudVisionUtils.detectSafeSearch(element_screenshot);
				
				//retrieve image logos from google cloud vision
				Set<Logo> logos = new HashSet<>();//CloudVisionUtils.extractImageLogos(element_screenshot);

				//retrieve image labels
				Set<Label> labels = CloudVisionUtils.extractImageLabels(element_screenshot);

				ImageElementState image_element = (ImageElementState)element_state;
				//image_element.setScreenshotUrl(element_screenshot_url);
				image_element.setFaces(faces);
				image_element.setLandmarkInfoSet(landmark_info_set);
				image_element.setImageSearchSet(image_search_set);

				image_element.setAdult(img_safe_search_annotation.getAdult());
				image_element.setRacy(img_safe_search_annotation.getRacy());
				image_element.setViolence(img_safe_search_annotation.getViolence());
				image_element.setLogos(logos);
				image_element.setLabels(labels);
				return image_element;
			} catch (MalformedURLException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return element_state;
	}


	/**
 	 * Constructs an {@link Element} from a JSOUP {@link Element element}
 	 * 
	 * @param xpath	the xpath of the element
	 * @param attributes	the attributes of the element
	 * @param element	the element
	 * @param web_elem	the web element
	 * @param classification	the classification of the element
	 * @param rendered_css_values	the rendered css values of the element
	 * @param screenshot_url	the screenshot url of the element
	 * @param css_selector	the css selector of the element
	 * 
	 * precondition: xpath != null
	 * precondition: !xpath.isEmpty()
	 * precondition: attributes != null
	 * precondition: element != null
	 * precondition: classification != null
	 * precondition: rendered_css_values != null
	 * precondition: screenshot_url != null
	 *
	 * @return {@link ElementState} based on {@link WebElement} and other params
	 * 
	 * @throws IOException if an error occurs while building the element state
	 */
	public static ElementState buildElementState(
			String xpath,
			Map<String, String> attributes,
			Element element,
			WebElement web_elem,
			ElementClassification classification,
			Map<String, String> rendered_css_values,
			String screenshot_url,
			String css_selector
	) throws IOException{
		return ElementStateAdapter.toElementState(
				xpath,
				attributes,
				element,
				web_elem,
				classification,
				rendered_css_values,
				screenshot_url,
				css_selector);
	}
	
	/**
 	 * Constructs an {@link Element} from a JSOUP {@link Element element}
 	 *
	 * @param xpath the xpath of the element
	 * @param attributes the attributes of the element
	 * @param element the element
	 * @param web_elem the web element
	 * @param classification the classification of the element
	 * @param rendered_css_values the rendered css values of the element
	 * @param screenshot_url the screenshot url of the element
	 * @param css_selector the css selector of the element
	 * @param landmark_info_set the landmark info set
	 * @param faces the faces
	 * @param image_search_set the image search set
	 * @param logos the logos
	 * @param labels the labels
	 * @param safe_search_annotation the safe search annotation
	 *
	 * @return {@link ElementState} based on {@link WebElement} and other params
	 * @throws IOException if an error occurs while building the element state
	 *
	 * precondition: xpath != null
	 * precondition: !xpath.isEmpty()
	 * precondition: attributes != null
	 * precondition: element != null
	 * precondition: classification != null
	 * precondition: rendered_css_values != null
	 * precondition: screenshot_url != null
	 *
	 */
	public static ElementState buildImageElementState(
			String xpath,
			Map<String, String> attributes,
			Element element,
			WebElement web_elem,
			ElementClassification classification,
			Map<String, String> rendered_css_values,
			String screenshot_url,
			String css_selector,
			Set<ImageLandmarkInfo> landmark_info_set,
			Set<ImageFaceAnnotation> faces,
			ImageSearchAnnotation image_search_set,
			Set<Logo> logos,
			Set<Label> labels,
			ImageSafeSearchAnnotation safe_search_annotation
	) throws IOException{
		return ImageElementStateAdapter.toImageElementState(
				xpath,
				attributes,
				element,
				web_elem,
				classification,
				rendered_css_values,
				screenshot_url,
				css_selector,
				landmark_info_set,
				faces,
				image_search_set,
				logos,
				labels,
				safe_search_annotation);
	}

	/**
	 * Process used by the web crawler to build {@link ElementState} list based on the xpaths on the page
	 * @param page_state the page state
	 * @param xpaths	the xpaths to build the page elements for
	 * @param audit_id	the audit id
	 * @param url	the url
	 * @param page_height	the page height
	 * @return the list of element states
	 * @throws MalformedURLException if the url is malformed
	 *
	 * precondition: xpaths != null
	 * precondition: audit_id != null
	 * precondition: url != null and is valid
	 * precondition: page_height != null and is positive
	 */
	public List<ElementState> buildPageElements(PageState page_state,
												List<String> xpaths,
												long audit_id,
												URL url,
												int page_height
	) throws MalformedURLException {
		assert page_state != null;
		assert xpaths != null;
		assert url != null;

		List<ElementState> elements = new ArrayList<>();
		Map<String, ElementState> elements_mapped = new HashMap<>();
		boolean rendering_incomplete = true;
		URL sanitized_url = new URL(BrowserUtils.sanitizeUserUrl( page_state.getUrl() ));
		String page_url = sanitized_url.toString();
		
		int cnt = 0;
		do {
			Browser browser = null;
			
			try {
				browser = getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
				browser.navigateTo(page_url);
				if(browser.is503Error()) {
					throw new ServiceUnavailableException("503 Error encountered. Starting over..");
				}
				browser.removeDriftChat();
				
				//get ElementState List by asking multiple bots to build xpaths in parallel
				//for each xpath then extract element state
				elements = getDomElementStates(page_state, xpaths, browser, elements_mapped, audit_id, sanitized_url, page_height);
				break;
			}
			catch (NullPointerException e) {
				log.warn("NPE thrown during element state extraction");
				//e.printStackTrace();
			}
			catch(MalformedURLException e) {
				log.warn("Unable to get browser connection to build page elements : "+page_url);
				continue;
			}
			catch(ServiceUnavailableException e) {
				log.warn("503 exception occurred while accessing "+page_url);
			}
			catch(WebDriverException e) {
				log.warn("Webdriver exception occurred ... "+page_url);
				//e.printStackTrace();
			}
			finally {
				if(browser != null) {
					browser.close();
				}
			}
			cnt++;
		}while(rendering_incomplete && cnt < 10000);

		return elements;
	}
	
	/**
	 * Process used by the web crawler to build {@link ElementState} list based on the xpaths on the page
	 *
	 * @param page_state the page state
	 * @param xpaths the xpaths to build the page elements for
	 * @param audit_id the audit id
	 * @param page_height the page height
	 * @param browser the browser
	 * @return the list of element states
	 * @throws MalformedURLException if the url is malformed
	 *s
	 * precondition: page_state != null
	 * precondition: xpaths != null
	 * precondition: browser != null
	 * precondition: audit_id != null
	 * precondition: page_height != null
	 */
	public List<ElementState> buildPageElementsWithoutNavigation(PageState page_state,
																List<String> xpaths,
																long audit_id,
																int page_height,
																Browser browser
	) throws MalformedURLException {
		assert page_state != null;
		assert xpaths != null;
		assert browser != null;

		List<ElementState> elements = new ArrayList<>();
		Map<String, ElementState> elements_mapped = new HashMap<>();
		//boolean rendering_incomplete = true;
		URL sanitized_url = new URL(BrowserUtils.sanitizeUserUrl( page_state.getUrl() ));
		
		elements = getDomElementStates(page_state, xpaths, browser, elements_mapped, audit_id, sanitized_url, page_height);

		return elements;
	}
	
	/**
	 * Open a browser and build element states
	 *
	 * @param elements the list of element states
	 * @param elements_mapped the map of element states
	 * @param page_state the page state
	 * @param xpaths the list of xpaths
	 * @param audit_id the audit id
	 * @param page_height the page height
	 *
	 * @return true if the element states were built successfully, false otherwise
	 * @throws MalformedURLException if the url is malformed
	 *
	 * precondition: elements != null
	 * precondition: elements_mapped != null
	 * precondition: page_state != null
	 * precondition: xpaths != null
	 * precondition: audit_id != null
	 * precondition: page_height != null and is positive
	 */
	@Retry(name="webdriver")
	private boolean openBrowserAndBuildElementStates(List<ElementState> elements,
													Map<String, ElementState> elements_mapped,
													PageState page_state,
													List<String> xpaths,
													long audit_id,
													int page_height
    ) throws MalformedURLException {
		URL sanitized_url = new URL(BrowserUtils.sanitizeUserUrl( page_state.getUrl() ));
		String page_url = sanitized_url.toString();
		Browser browser = null;
		
		try {
			browser = getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
			browser.navigateTo(page_url);
			if(browser.is503Error()) {
				throw new ServiceUnavailableException("503 Error encountered. Starting over..");
			}
			browser.removeDriftChat();
			
			//get ElementState List by asking multiple bots to build xpaths in parallel
			//for each xpath then extract element state
			elements = getDomElementStates(page_state, xpaths, browser, elements_mapped, audit_id, sanitized_url, page_height);
			return false;
		}
		catch (NullPointerException e) {
			log.warn("NPE thrown during element state extraction");
			//e.printStackTrace();
		}
		catch(MalformedURLException e) {
			log.warn("Unable to get browser connection to build page elements : "+page_url);
			return false;
		}
		catch(ServiceUnavailableException e) {
			log.warn("503 exception occurred while accessing "+page_url);
		}	
		finally {
			if(browser != null) {
				browser.close();
			}
		}
		return true;
	}
	
	/**
	 * identify and collect data for elements within the Document Object Model
	 * @param page_state	the page state
	 * @param xpaths	the xpaths
	 * @param browser	the browser
	 * @param element_states_map	the element states map
	 * @param audit_record_id	the audit record id
	 * @param url	the url
	 * @param page_height	the page height
	 * @return the list of element states
	 * 
	 * precondition: xpaths != null
	 * precondition: browser != null
	 * precondition: element_states_map != null
	 * precondition: page_state != null
	 */
	private List<ElementState> getDomElementStates(
			PageState page_state,
			List<String> xpaths,
			Browser browser,
			Map<String, ElementState> element_states_map,
			long audit_record_id,
			URL url,
			int page_height
	) {
		assert xpaths != null;
		assert browser != null;
		assert element_states_map != null;
		assert page_state != null;
		
		List<ElementState> visited_elements = new ArrayList<>();
		List<ElementState> filtered_elements = new ArrayList<>();
		Map<String, Boolean> overlapped_elements = new HashMap<>();

		String body_src = extractBody(page_state.getSrc());
		
		Document html_doc = Jsoup.parse(body_src);
		String host = url.getHost();
				
		for(String xpath : xpaths) {
			if(element_states_map.containsKey(xpath)) {
				continue;
			}
			
			try {
				WebElement web_element = browser.findElement(xpath);
				if(web_element == null) {
					continue;
				}
				Dimension element_size = web_element.getSize();
				Point element_location = web_element.getLocation();
				browser.scrollToElement(xpath, web_element);
				if(element_location.getY() >= page_height || element_size.getHeight() >= page_height) {
					continue;
				}
				
				//check if element is visible in pane and if not then continue to next element xpath
				if( !web_element.isDisplayed()
						|| !hasWidthAndHeight(element_size)
						|| doesElementHaveNegativePosition(element_location)) {
					continue;
				}
				
				String css_selector = generateCssSelectorFromXpath(xpath);
				String element_screenshot_url = "";

				BufferedImage element_screenshot = null;
				
				if(!BrowserUtils.isLargerThanViewport(element_size, page_state.getViewportWidth(), page_state.getViewportHeight())) {
					try {
							
						//extract element screenshot from full page screenshot
						//BufferedImage element_screenshot = page_screenshot.getSubimage(element_location.getX(), element_location.getY(), width, height);
						element_screenshot = browser.getElementScreenshot(web_element);
						String screenshot_checksum = ImageUtils.getChecksum(element_screenshot);
						
						element_screenshot_url = googleCloudStorage.saveImage(element_screenshot,
						host, screenshot_checksum, BrowserType.create(browser.getBrowserName()));
					}
					catch( Exception e) {
						//do nothing
						/*
						log.warn("element height :: "+element_size.getHeight());
						log.warn("Element Y location ::  "+ element_location.getY());
						log.warn("element width :: "+element_size.getWidth());
						log.warn("Element X location ::  "+ element_location.getX());
						*/
						try {
							BufferedImage full_page_screenshot = ImageIO.read(new URL(page_state.getFullPageScreenshotUrl()));
							int width = element_size.getWidth();
							int height = element_size.getHeight();
							
							if( (element_location.getX() + element_size.getWidth()) > full_page_screenshot.getWidth() ) {
								width = full_page_screenshot.getWidth() - element_location.getX()-1;
							}
							
							if( (element_location.getY() + element_size.getHeight()) > full_page_screenshot.getHeight() ) {
								height = full_page_screenshot.getHeight() - element_location.getY()-1;
							}
							
							element_screenshot = full_page_screenshot.getSubimage(element_location.getX(), element_location.getY(), width, height);
						}
						catch(Exception e1){
							e1.printStackTrace();
						}
						//e.printStackTrace();
					}
				}
				else {
					//TODO: extract image from full page screenshot manually
				}
				
				
				Map<String, String> rendered_css_props = browser.getComputedCssProperties(web_element);
				Map<String, String> attributes = browser.extractAttributes(web_element);

				ElementClassification classification = null;
				List<WebElement> children = getChildElements(web_element);
				
				if(children.isEmpty()) {
					classification = ElementClassification.LEAF;
				}
				else {
					classification = ElementClassification.ANCESTOR;
				}
				
				//load json element
				Elements elements = Xsoup.compile(xpath).evaluate(html_doc).getElements();
				if(elements.size() == 0) {
					log.warn("NO ELEMENTS WITH XPATH FOUND :: "+xpath);
				}
								
				Element element = elements.first();
				

				if(isImageElement(extractTagFromXpath(xpath)) && element_screenshot != null) {
					//retrieve image landmark properties from google cloud vision
					Set<ImageLandmarkInfo> landmark_info_set = CloudVisionUtils.extractImageLandmarks(element_screenshot);
					
					//retrieve image faces properties from google cloud vision
					Set<ImageFaceAnnotation> faces = CloudVisionUtils.extractImageFaces(element_screenshot);
					
					//retrieve image reverse image search properties from google cloud vision
					ImageSearchAnnotation image_search_set = CloudVisionUtils.searchWebForImageUsage(element_screenshot);
					ImageSafeSearchAnnotation img_safe_search_annotation = CloudVisionUtils.detectSafeSearch(element_screenshot);
					
					//retrieve image logos from google cloud vision
					Set<Logo> logos = new HashSet<>();//CloudVisionUtils.extractImageLogos(element_screenshot);

					//retrieve image labels
					Set<Label> labels = CloudVisionUtils.extractImageLabels(element_screenshot);
					ElementState element_state = buildImageElementState(xpath,
																		attributes,
																		element,
																		web_element,
																		classification,
																		rendered_css_props,
																		element_screenshot_url,
																		css_selector,
																		landmark_info_set,
																		faces,
																		image_search_set,
																		logos,
																		labels,
																		img_safe_search_annotation);
					
					element_states_map.put(xpath, element_state);
					visited_elements.add(element_state);
				}
				else {
					ElementState element_state = buildElementState(xpath,
																	attributes,
																	element,
																	web_element,
																	classification,
																	rendered_css_props,
																	element_screenshot_url,
																	css_selector);
					element_states_map.put(xpath, element_state);
					visited_elements.add(element_state);
				}
				
				//filter all elements that have dimensions that are within another element and have a lower z-index
				for(ElementState element1: visited_elements) {
					if(filtered_elements.contains(element1)) {
						continue;
					}
					boolean overlap_exists = false;
					for(ElementState element2: visited_elements) {
						if(element1.getKey().equals(element2.getKey()) || overlapped_elements.containsKey(element2.getKey())) {
							continue;
						}
						
						//boolean values for equality of element1 and element2 x and y value
						boolean x_overlap = element1.getXLocation() >= element2.getXLocation() && (element1.getXLocation()+element1.getWidth()) <= (element2.getXLocation()+element2.getWidth());
						boolean y_overlap = element1.getYLocation() >= element2.getYLocation() && (element1.getYLocation()+element1.getHeight()) <= (element2.getYLocation()+element2.getHeight());
						
						//log.warn("element1 z-index :: "+element1.getRenderedCssValues().get("z-index"));
						//log.warn("element2 z-index :: "+element2.getRenderedCssValues().get("z-index"));

						String element1_z_index = element1.getRenderedCssValues().get("z-index");
						if(element1_z_index.contentEquals("auto")) {
							element1_z_index = "0";
						}
						String element2_z_index = element2.getRenderedCssValues().get("z-index");
						if(element2_z_index.contentEquals("auto")) {
							element2_z_index = "0";
						}
						boolean z_index_overlap = Integer.parseInt(element1_z_index) < Integer.parseInt(element2_z_index);
						if(x_overlap && y_overlap && z_index_overlap) {
							overlap_exists = true;
							break;
						}
					}
					
					if(!overlap_exists) {
						filtered_elements.add(element1);
					}
					else {
						overlapped_elements.put(element1.getKey(), Boolean.TRUE);
					}
				}
			}
			catch(NoSuchElementException e) {
				//log.warn("No such element found :: "+xpath+"       ;;    on page : "+page_state.getUrl());
				element_states_map.put(xpath, null);
			}
			catch (StaleElementReferenceException e) {
				log.warn("Stale element exception thrown while retrieving element with xpath :: "+xpath +"; On page with url ::  "+page_state.getUrl());
				element_states_map.put(xpath, null);
			}
			catch(NullPointerException e) {
				log.warn("There was an NPE error finding element with xpath .... "+xpath + "   ;;   ON page :: "+page_state.getUrl());
				//e.printStackTrace();
			} catch (IOException e) {
				log.warn("IOException occurred while building elements");
				//e.printStackTrace();
			}
		}
		return filtered_elements;
	}
	
	/**
	 * Retrieves transparency value from rgba string
	 * 
	 * @param css_value the css value to check
	 * @return true if the css value has transparency, false otherwise
	 */
	private boolean hasTransparency(String css_value) {
		assert css_value != null;
		assert !css_value.isEmpty();
		
		assert css_value.startsWith("rgba(");
		if(css_value.startsWith("rgb(")) {
			return false;
		}
		
		css_value = css_value.replace("rgba(", "");
		css_value = css_value.replace(")", "");
		String[] rgba = css_value.split(",");
		double transparency_value = Double.parseDouble(rgba[3].trim());

		return transparency_value < 1.0;
	}

	/**
	 * Checks if {@link Element element} is a part of a slideshow container
	 * 
	 * @param element the element to check
	 * @return true if the element is a part of a slideshow container, false otherwise
	 */
	private static boolean isSliderElement(Element element) {
		for(org.jsoup.nodes.Attribute attr : element.attributes()) {
			if(attr.getValue().toLowerCase().contains("slider") || attr.getKey().toLowerCase().contains("slider")) {
				return true;
			}
		}
		
		return false;
	}

	/**
	 * Extracts metadata from a string
	 *
	 * @param src the string to extract metadata from
	 * @return the set of metadata
	 *
	 * precondition: src != null
	 */
	public static Set<String> extractMetadata(String src) {
		assert src != null;
		assert src != null;

		Document html_doc = Jsoup.parse(src);
		Elements meta_tags = html_doc.getElementsByTag("meta");
		Set<String> meta_tag_html = new HashSet<String>();
		
		for(Element meta_tag : meta_tags) {
			meta_tag_html.add(meta_tag.outerHtml());
		}
		return meta_tag_html;
	}

	/**
	 * Extracts stylesheets from a string
	 *
	 * @param src the string to extract stylesheets from
	 * @return the set of stylesheets
	 *
	 * precondition: src != null
	 */
	public static Set<String> extractStylesheets(String src) {
		assert src != null;
		assert src != null;

		Document html_doc = Jsoup.parse(src);
		Elements link_tags = html_doc.getElementsByTag("link");
		Set<String> stylesheet_urls = new HashSet<String>();
		
		for(Element link_tag : link_tags) {
			stylesheet_urls.add(link_tag.absUrl("href"));
		}
		return stylesheet_urls;
	}

	/**
	 * Extracts script urls from a string
	 *
	 * @param src the string to extract script urls from
	 * @return the set of script urls
	 *
	 * precondition: src != null
	 */
	public static Set<String> extractScriptUrls(String src) {
		assert src != null;
		assert src != null;

		Document html_doc = Jsoup.parse(src);
		Elements script_tags = html_doc.getElementsByTag("script");
		Set<String> script_urls = new HashSet<String>();
		
		for(Element script_tag : script_tags) {
			String src_url = script_tag.absUrl("src");
			if(src_url != null && !src_url.isEmpty()) {
				script_urls.add(script_tag.absUrl("src"));
			}
		}
		return script_urls;
	}

	/**
	 * Extracts icon links from a string
	 *
	 * @param src the string to extract icon links from
	 * @return the set of icon links
	 *
	 * precondition: src != null
	 */
	public static Set<String> extractIconLinks(String src) {
		assert src != null;
		assert src != null;

		Document html_doc = Jsoup.parse(src);
		Elements icon_tags = html_doc.getElementsByTag("link");
		Set<String> icon_urls = new HashSet<String>();

		for(Element icon_tag : icon_tags) {
			if(icon_tag.attr("rel").contains("icon")){
				icon_urls.add(icon_tag.absUrl("href"));
			}
		}
		return icon_urls;
	}

	/**
	 * Retrieves the page source from a {@link Browser}
	 * 
	 * @param browser the browser (must not be null)
	 * @param sanitized_url the sanitized url (must not be null)
	 * 
	 * @return the page source
	 * @throws MalformedURLException if the url is malformed
	 * @throws IllegalArgumentException if browser is null
	 * @throws IllegalArgumentException if sanitized_url is null
	 */
	public String getPageSource(Browser browser, URL sanitized_url) throws MalformedURLException {
		assert browser != null;
		assert sanitized_url != null;
		
		return browser.getSource();
	}

	/**
	 *
	 * Navigates to a url, checks that the service is available, then removes drift
	 * 	chat client from page if it exists. Finally it builds a {@link PageState}
	 *
	 *Constructs a page object that contains all child elements that are considered to be potentially expandable.
	 * @param browser the browser
	 * @param audit_record_id the id of the audit record
	 * @param browser_url the browser url
	 * 
	 * @return page {@linkplain PageState}
	 * 
	 * @throws WebDriverException if the web driver exception occurs
	 * @throws StorageException if the storage exception occurs
	 * @throws IOException if the io exception occurs
	 * 
	 * precondition: browser != null
	 */
	public PageState buildPageState( Browser browser, long audit_record_id, String browser_url) throws WebDriverException, IOException {
		return pageStateAdapter.toPageState(browser, audit_record_id, browser_url);
	}
	

	/**
	 * Identifies and collects data for elements within the Document Object Model
	 * 
	 * @param domain_map_id the id of the domain map
	 * @param full_page_screenshot the full page screenshot
	 * @param page_state the page state
	 * @param xpaths the xpaths
	 * @param browser the browser
	 * @param browser_url the browser url
	 * 
	 * @return List of ElementStates
	 * 
	 * @throws Exception if an error occurs
	 * @throws XPathExpressionException if an error occurs
	 * 
	 * precondition: xpaths != null
	 * precondition: browser != null
	 * precondition: page_state != null
	 */
	public List<ElementState> getDomElementStates(
			PageState page_state,
			List<String> xpaths,
			Browser browser,
			long domain_map_id,
			BufferedImage full_page_screenshot,
			String browser_url
	) throws Exception {
		assert xpaths != null;
		assert browser != null;
		assert page_state != null;
		
		List<ElementState> visited_elements = new ArrayList<>();
		List<ElementState> image_elements = new ArrayList<>();
		String body_src = extractBody(page_state.getSrc());
		
		Document html_doc = Jsoup.parse(body_src);
		String host = (new URL(browser_url)).getHost();
		xpaths = xpaths.parallelStream().filter(Objects::nonNull).collect(Collectors.toList());

		//iterate over xpaths to build ElementStates without screenshots
		for(String xpath : xpaths) {
			//load JSOUP element
			Elements elements = Xsoup.compile(xpath).evaluate(html_doc).getElements();
			Element element = elements.first();
			if(page_state.getUrl().contains("blog")){
				log.warn("reviewing element with xpath1 = "+xpath);
			}
			String tag_name = element.tagName();
			//check if element is visible in pane and if not then continue to next element xpath
			if( isStructureTag(tag_name) || !ElementStateUtils.isInteractiveElement(element)){
				if(page_state.getUrl().contains("blog")){
					log.warn("skipping xpath = "+xpath);
				}
				continue;
			}
			if(page_state.getUrl().contains("blog")){
				log.warn("reviewing element with xpath2 = "+xpath);
			}
			WebElement web_element = browser.findElement(xpath);
			if(web_element == null) {
				log.warn("web element is null : "+xpath+"  ;;   for page = "+page_state.getKey());
				continue;
			}
			if(page_state.getUrl().contains("blog")){
				log.warn("reviewing element with xpath3 = "+xpath);
			}

			Rectangle rect = web_element.getRect();
			Dimension element_size = new Dimension(rect.getWidth(), rect.getHeight());
			Point element_location = new Point(rect.getX(), rect.getY());
			if( doesElementHaveNegativePosition(element_location)
				|| BrowserUtils.isHidden(element_location, element_size)
				|| rect.getHeight() <= 0
				|| rect.getWidth() <= 0
				|| !web_element.isDisplayed()
			){
				if(page_state.getUrl().contains("blog")){
					log.warn("skipping element because of negative position or no dimensions or not displayed = "+xpath);
				}
				continue;
			}

			if(page_state.getUrl().contains("blog")){
				log.warn("reviewing element with xpath4 = "+xpath);
			}

			String css_selector = generateCssSelectorFromXpath(xpath);
			ElementClassification classification = ElementClassification.UNKNOWN;
			if(page_state.getUrl().contains("blog")){
				log.warn("reviewing element with xpath5 = "+xpath);
			}
			if(isImageElement(tag_name)) {
				ElementState element_state = buildImageElementState(xpath,
																	new HashMap<>(),
																	element,
																	classification,
																	new HashMap<>(),
																	null,
																	css_selector,
																	null,
																	null,
																	null,
																	null,
																	null,
																	element_size,
																	element_location);
				
				ElementState element_record = element_state_service.findByDomainMapAndKey(domain_map_id, element_state);
				if(element_record == null) {
					element_state = enrichElementState(browser, web_element, element_state, full_page_screenshot, host);
					element_state = enrichImageElement(element_state);
					//element_record = element_state_service.save(domain_map_id, page_state.getId(), element_state);
					visited_elements.add(element_state);
				}
				else{
					visited_elements.add(element_record);
				}
			}
			else {
				if(page_state.getUrl().contains("blog")){
					log.warn("reviewing element with xpath6 (element-build) = "+xpath);
				}
				try{
					ElementState element_state = buildElementState(xpath,
																new HashMap<>(),
																element,
																classification,
																new HashMap<>(),
																null,
																css_selector,
																element_size,
																element_location);
					
					ElementState element_record = element_state_service.findByDomainMapAndKey(domain_map_id, element_state);
					if(element_record == null) {
						if(page_state.getUrl().contains("blog")){
							log.warn("reviewing element with xpath6 (element-enrichment) = "+xpath);
						}
						element_state = enrichElementState(browser, web_element, element_state, full_page_screenshot, host);
						if(page_state.getUrl().contains("blog")){
							log.warn("reviewing element with xpath6 (background enrichment) = "+xpath);
						}
						element_state = ElementStateUtils.enrichBackgroundColor(element_state);
						//element_record = element_state_service.save(domain_map_id, page_state.getId(), element_state);
						visited_elements.add(element_state);
					}
					else{
						visited_elements.add(element_record);
					}
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		}
		visited_elements.addAll(image_elements);

		return visited_elements;
	}

	/**
	 * Checks if element tag is 'img'
	 *
	 * @param tag_name the tag name
	 * @return true if the element tag is 'img', otherwise false
	 */
	private boolean isImageElement(String tag_name) {
		return "img".equalsIgnoreCase(tag_name);
	}

	/**
	 * Extracts the HTML tag from the last segment of an xpath. Mode-agnostic —
	 * doesn't round-trip to a driver or server. Matches the shape of xpaths
	 * produced by {@link #extractAllUniqueElementXpaths} and
	 * {@link #generateXpath}, and the xpaths stored in
	 * {@code ElementState.getXpath()}.
	 *
	 * <p>Used by {@link #getDomElementStates} so the tag-based filter doesn't
	 * need to call {@code WebElement.getTagName()} — which would throw on a
	 * {@link com.looksee.services.browser.RemoteWebElement} (phase-3c-deferred
	 * surface). Phase 3d unblocks PageBuilder's remote-mode audit by sourcing
	 * the tag from data we already have.
	 *
	 * <p><b>Predicate-aware scanning.</b> A naive {@code lastIndexOf('/')}
	 * fails on xpaths whose predicates contain quoted slashes — e.g.
	 * {@code //a[contains(@title,'foo/bar')]} — because the slash inside the
	 * string literal looks identical to a path separator. The implementation
	 * tracks bracket depth and quote state to find the last <em>structural</em>
	 * slash (the one between path segments, not the one inside a literal).
	 *
	 * <p>If the xpath is malformed, empty, or null, returns {@code ""} —
	 * callers treat that as "unknown tag" and fall through their tag-name
	 * checks naturally.
	 */
	static String extractTagFromXpath(String xpath) {
		if (xpath == null || xpath.isEmpty()) return "";
		xpath = unwrapIndexedXpath(xpath);

		// Scan once tracking bracket depth + quote state. tailStart is the
		// position just after the last '/' that's outside any [...] predicate
		// and outside any quoted string.
		int tailStart = 0;
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < xpath.length(); i++) {
			char c = xpath.charAt(i);
			if (quote != 0) {
				if (c == quote) quote = 0;
			} else if (c == '\'' || c == '"') {
				quote = c;
			} else if (c == '[') {
				depth++;
			} else if (c == ']') {
				if (depth > 0) depth--;
			} else if (c == '/' && depth == 0) {
				tailStart = i + 1;
			}
		}

		// Once we're past the last structural slash, the tag name is text up
		// to the first '['. Tag names can't legally contain '[' or quotes,
		// so a plain indexOf is safe here.
		int bracket = xpath.indexOf('[', tailStart);
		String tag = bracket >= 0 ? xpath.substring(tailStart, bracket) : xpath.substring(tailStart);

		// Strip namespace prefix (e.g. "svg:rect" → "rect").
		int colon = tag.indexOf(':');
		return colon >= 0 ? tag.substring(colon + 1) : tag;
	}

	/**
	 * Removes the parenthesized wrapper RemoteBrowser and uniqifyXpath add to
	 * indexed multi-match xpaths, for example {@code (//form)[1]}.
	 */
	private static String unwrapIndexedXpath(String xpath) {
		while (xpath.startsWith("(")) {
			int parenDepth = 0;
			char quote = 0;
			int closingParen = -1;
			for (int i = 0; i < xpath.length(); i++) {
				char c = xpath.charAt(i);
				if (quote != 0) {
					if (c == quote) quote = 0;
				} else if (c == '\'' || c == '"') {
					quote = c;
				} else if (c == '(') {
					parenDepth++;
				} else if (c == ')' && --parenDepth == 0) {
					closingParen = i;
					break;
				}
			}

			if (closingParen < 0
					|| !xpath.substring(closingParen + 1).matches("(\\[\\d+\\])*")) {
				break;
			}
			xpath = xpath.substring(1, closingParen);
		}
		return xpath;
	}

	/**
	 * Checks if {@link WebElement element} is visible in the current viewport window or not
	 *
	 * @param viewport_size {@link Browser browser} connection to use
	 * @param position {@link Point position} of the element
	 * @param size {@link Dimension size} of the element
	 *
	 * @return true if element is rendered within viewport, otherwise false
	 *
	 * precondition: viewport_size != null
	 * precondition: position != null
	 * precondition: size != null
	 */
	public static boolean doesElementFitInViewport(Dimension viewport_size, Point position, Dimension size){
		assert viewport_size != null;
		assert position != null;
		assert size != null;

		int height = size.getHeight();
		int width = size.getWidth();

		return width < (viewport_size.getWidth())
				&& height < (viewport_size.getHeight());
	}

	/**
	 * Performs image enrichment in parallel for all elements in the given list
	 * @param element_states list of element states to enrich
	 * @return list of enriched element states
	 *
	 * precondition: element_states != null
	 */
	public List<ElementState> enrichImageElement(List<ElementState> element_states)
	{
		assert element_states != null;
		assert element_states != null;

		return element_states.parallelStream().map(element_state -> {
			if(element_state instanceof ImageElementState && !element_state.getScreenshotUrl().isEmpty()) {
				BufferedImage element_screenshot;
				try {
					element_screenshot = ImageIO.read(new URL(element_state.getScreenshotUrl()));

					//retrieve image landmark properties from google cloud vision
					//Set<ImageLandmarkInfo> landmark_info_set = CloudVisionUtils.extractImageLandmarks(element_screenshot);
					Set<ImageLandmarkInfo> landmark_info_set = null;
					//retrieve image faces properties from google cloud vision
					//Set<ImageFaceAnnotation> faces = CloudVisionUtils.extractImageFaces(element_screenshot);
					Set<ImageFaceAnnotation> faces = null;
					//retrieve image reverse image search properties from google cloud vision
					ImageSearchAnnotation image_search_set = CloudVisionUtils.searchWebForImageUsage(element_screenshot);
					ImageSafeSearchAnnotation img_safe_search_annotation = CloudVisionUtils.detectSafeSearch(element_screenshot);
					
					//retrieve image logos from google cloud vision
					Set<Logo> logos = new HashSet<>();//CloudVisionUtils.extractImageLogos(element_screenshot);

					//retrieve image labels
					Set<Label> labels = CloudVisionUtils.extractImageLabels(element_screenshot);

					ImageElementState image_element = (ImageElementState)element_state;
					//image_element.setScreenshotUrl(element_screenshot_url);
					image_element.setFaces(faces);
					image_element.setLandmarkInfoSet(landmark_info_set);
					image_element.setImageSearchSet(image_search_set);

					image_element.setAdult(img_safe_search_annotation.getAdult());
					image_element.setRacy(img_safe_search_annotation.getRacy());
					image_element.setViolence(img_safe_search_annotation.getViolence());
					image_element.setLogos(logos);
					image_element.setLabels(labels);
					return image_element;
				} catch (MalformedURLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return null;

		}).filter(e -> e==null)
		.collect(Collectors.toList());
	}

	/**
	 * Enriches elements using cloud vision utils
	 *
	 * @param element_state the element state to enrich
	 * @return the enriched element state
	 *
	 * precondition: element_state != null
	 */
	public ElementState enrichImageElement(ElementState element_state)
	{
		assert element_state != null;

		if(element_state instanceof ImageElementState
				&& element_state.getScreenshotUrl() != null
				&& !element_state.getScreenshotUrl().isEmpty())
		{
			BufferedImage element_screenshot;
			try {
				element_screenshot = ImageIO.read(new URL(element_state.getScreenshotUrl()));

				//retrieve image landmark properties from google cloud vision
				//Set<ImageLandmarkInfo> landmark_info_set = CloudVisionUtils.extractImageLandmarks(element_screenshot);
				Set<ImageLandmarkInfo> landmark_info_set = null;
				//retrieve image faces properties from google cloud vision
				//Set<ImageFaceAnnotation> faces = CloudVisionUtils.extractImageFaces(element_screenshot);
				Set<ImageFaceAnnotation> faces = null;
				//retrieve image reverse image search properties from google cloud vision
				ImageSearchAnnotation image_search_set = CloudVisionUtils.searchWebForImageUsage(element_screenshot);
				ImageSafeSearchAnnotation img_safe_search_annotation = CloudVisionUtils.detectSafeSearch(element_screenshot);
				
				//retrieve image logos from google cloud vision
				Set<Logo> logos = new HashSet<>();//CloudVisionUtils.extractImageLogos(element_screenshot);

				//retrieve image labels
				Set<Label> labels = CloudVisionUtils.extractImageLabels(element_screenshot);

				ImageElementState image_element = (ImageElementState)element_state;
				//image_element.setScreenshotUrl(element_screenshot_url);
				image_element.setFaces(faces);
				image_element.setLandmarkInfoSet(landmark_info_set);
				image_element.setImageSearchSet(image_search_set);

				if(img_safe_search_annotation != null){
					image_element.setAdult(img_safe_search_annotation.getAdult());
					image_element.setRacy(img_safe_search_annotation.getRacy());
					image_element.setViolence(img_safe_search_annotation.getViolence());
				}
				image_element.setLogos(logos);
				image_element.setLabels(labels);
				return image_element;
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return element_state;
	}
	
	/**
     * Generates an XPath for the given element using indexes for each tag to specify its location
     * relative to other tags with the same name.
     *
     * @param element The JSoup Element for which to generate the XPath.
     * @return A string representing the XPath of the element.
     *
     * precondition: element != null
     */
    public static String getXPath(Element element) {
        assert element != null;

        StringBuilder xpath = new StringBuilder();

        // Traverse up the DOM tree to construct the XPath
        while (element != null) {
            int index = getElementIndex(element);
            String tagName = element.tagName();

            // Construct the XPath part for this element
            xpath.insert(0, "/" + tagName + "[" + index + "]");

            // Move up to the parent element
            Node parent = element.parent();
            if (parent instanceof Element && !"body".equals(((Element)parent).tagName())) {
                element = (Element) parent;
            } else {
				xpath.insert(0, "//body");
                break;
            }
        }

        // Return the full XPath
        return xpath.toString();
    }

    /**
     * Returns the 1-based index of the element among its siblings with the same tag name.
     *
     * @param element The element whose index to determine.
     * @return The 1-based index of the element.
     */
    private static int getElementIndex(Element element) {
        int index = 1; // XPath indices are 1-based
        Element previousSibling = element.previousElementSibling();

        // Count the number of preceding siblings with the same tag name
        while (previousSibling != null) {
            if (previousSibling.tagName().equals(element.tagName())) {
                index++;
            }
            previousSibling = previousSibling.previousElementSibling();
        }

        return index;
    }

	/**
	 *Constructs a page object that contains all child elements that are considered to be potentially expandable.
	 * 
	 * @param page_src {@link String} representing the source of the page
	 * @param page_url {@link String} representing the url of the page
	 * @param title {@link String} representing the title of the page
	 * 
	 * @return {@link Page} representing the page
	 * 
	 * precondition: page_url != null
	 * precondition: page_src != null
	 * 
	 * @throws IOException if an error occurs while building the page
	 * @throws XPathExpressionException if an error occurs while building the page
	 */
	public Page buildPage( 	String page_src,
							String page_url,
							String title
    ) throws IOException, XPathExpressionException{
		assert page_url != null;
		assert page_src != null;
		log.warn("building page....");
		String url_without_params = BrowserUtils.sanitizeUrl(page_url, true);
		
		//Element root = html_doc.getElementsByTag("body").get(0);
		List<String> raw_stylesheets = HtmlUtils.extractStylesheets(page_src);
		List<RuleSet> rule_sets = HtmlUtils.extractRuleSetsFromStylesheets(raw_stylesheets, new URL(page_url)); 
		
		String clean_source = HtmlUtils.cleanSrc(page_src);
		URL clean_url = new URL(url_without_params);
		List<com.looksee.models.Element> elements = extractElements(clean_source, clean_url, rule_sets);
				
		Page page = new Page(
				elements,
				clean_source,
				title,
				clean_url.toString(),
				clean_url.getPath());

		
		Page record = page_service.findByKey(page.getKey());
		if(record != null) {
			return record;
		}
		log.warn("built page...now saving page state...");
		return page;
	}
	
	/**
 	 * Constructs an {@link Element} from a JSOUP {@link Element element}
 	 * 
	 * @param xpath {@link String} representing the xpath of the element
	 * @param attributes {@link Map} of {@link String}s to {@link String}s representing the attributes of the element
	 * @param jsoup_element {@link Element} to build
	 * @param classification {@link ElementClassification} of the element
	 * @param pre_rendered_css_values {@link Map} of {@link String}s to {@link String}s representing the rendered css values of the element
	 * 
	 * @return {@link com.looksee.models.Element} representing the element
	 * 
	 * precondition: xpath != null
	 * precondition: !xpath.isEmpty()
	 * precondition: attributes != null
	 * precondition: element != null
	 * precondition: classification != null
	 * precondition: rendered_css_values != null
	 */
	public static com.looksee.models.Element buildElement(
			String xpath,
			Map<String, String> attributes,
			Element jsoup_element,
			ElementClassification classification,
			Map<String, String> pre_rendered_css_values
	) {
		assert xpath != null && !xpath.isEmpty();
		assert attributes != null;
		assert jsoup_element != null;
		assert classification != null;
		assert pre_rendered_css_values != null;
		
		com.looksee.models.Element element = new com.looksee.models.Element(jsoup_element.ownText(), xpath, jsoup_element.tagName(), attributes, pre_rendered_css_values, jsoup_element.html(), classification, jsoup_element.outerHtml());

		return element;
	}

	/** MESSAGE GENERATION METHODS **/

	/**
	 * Extracts all forms including the child inputs and associated labels.
	 *
	 * @param account_id id of the account
	 * @param domain {@link Domain} to extract forms from
	 * @param browser {@link Browser} to extract forms from
	 * @return {@link Set} of {@link Form}s
	 * 
	 * precondition: account_id > 0
	 * precondition: domain != null
	 * precondition: browser != null
	 * 
	 * @throws Exception if an error occurs while extracting forms
	 */
	@Deprecated
	public Set<Form> extractAllForms(long account_id, Domain domain, Browser browser) throws Exception {
		assert account_id > 0;
		assert domain != null;
		assert browser != null;

		Set<Form> form_list = new HashSet<Form>();
		log.info("extracting forms from page with url    ::     "+browser.getCurrentUrl());
		List<WebElement> form_elements = browser.findElements("//form");

		//String host = domain.getHost();
		for(WebElement form_elem : form_elements){
			//BrowserUtils.detectShortAnimation(browser, page.getUrl());
			if(!form_elem.isDisplayed() || doesElementHaveNegativePosition(form_elem.getLocation())) {
				continue;
			}
			
			//BufferedImage img = browser.getElementScreenshot(form_elem);
			//String checksum = PageState.getFileChecksum(img);
			//Map<String, String> css_map = CssUtils.loadCssProperties(form_elem);
			String xpath = uniqifyXpath(form_elem, "//form", browser);
			com.looksee.models.Element form_tag = new com.looksee.models.Element(
					form_elem.getText(),
					xpath,
					extractTagFromXpath(xpath),
					browser.extractAttributes(form_elem),
					new HashMap<>(),
					form_elem.getAttribute("innerHTML"),
					ElementClassification.ANCESTOR,
					form_elem.getAttribute("outerHTML"));

			form_tag = element_service.saveFormElement(form_tag);
			
			/*
			double[] weights = new double[1];
		
			Set<Form> forms = domain_service.getForms(account_id, domain.getUrl());
			Form form = new Form(form_tag, 
								 new ArrayList<com.looksee.models.Element>(), 
								 findFormSubmitButton(form_elem, browser),
								 "Form #"+(forms.size()+1), 
								 weights, 
								 FormType.UNKNOWN, 
								 new Date(), 
								 FormStatus.DISCOVERED );

			List<WebElement> input_elements =  form_elem.findElements(By.tagName("input"));
			
			input_elements = BrowserService.filterNonDisplayedElements(input_elements);
			form.setFormFields(buildFormFields(account_id, input_elements, browser));

			log.info("weights :: "+ form.getPrediction());
			form.setType(FormType.UNKNOWN);
			form.setDateDiscovered(new Date());
			log.info("form record discovered date :: "+form.getDateDiscovered());

			Form form_record = form_service.findByKey(account_id, domain.getUrl(), form.getKey());
			if(form_record != null) {
				continue;
			}

			int form_count = domain_service.getFormCount(account_id, domain.getUrl());
			form.setName("Form #"+(form_count+1));
			log.info("name :: "+form.getName());
			
			form_list.add(form);
			*/
		}
		return form_list;
	}
	
	/**
	 * Builds form fields from a list of input elements
	 * 
	 * @param account_id id of the account
	 * @param input_elements {@link List} of {@link WebElement}s to build form fields from
	 * @param browser {@link Browser} to build form fields from
	 * @return {@link List} of {@link com.looksee.models.Element}s representing the form fields
	 */
	private List<com.looksee.models.Element> buildFormFields(long account_id, List<WebElement> input_elements, Browser browser) throws IOException {
		List<com.looksee.models.Element> elements = new ArrayList<>();
		for(WebElement input_elem : input_elements){
			boolean submit_elem_found = false;

			Map<String, String> attributes = browser.extractAttributes(input_elem);
			for(String attribute : attributes.keySet()){
				if(attributes.get(attribute).contains("submit")){
					submit_elem_found = true;
					break;
				}
			}

			if(submit_elem_found){
				continue;
			}
			
			if(input_elem.getLocation().getX() < 0 || input_elem.getLocation().getY() < 0){
				log.warn("element location x or y are negative");
				continue;
			}
			
			com.looksee.models.Element input_tag = new com.looksee.models.Element(input_elem.getText(),
					generateXpath(input_elem, browser, attributes), 
					input_elem.getTagName(), 
					attributes, 
					new HashMap<>(), 
					input_elem.getAttribute("innerHTML"), 
					input_elem.getAttribute("outerHTML"));
			com.looksee.models.Element tag_record = element_service.findByKeyAndUserId(account_id, input_tag.getKey());
			if( tag_record != null ) {
				input_tag = tag_record;
			}
			
			/*
			if( input_tag.getViewportScreenshotUrl() == null  || input_tag.getViewportScreenshotUrl().isEmpty()) {
				BufferedImage img = browser.getElementScreenshot(input_elem);
				String checksum = PageState.getFileChecksum(img);
				
				String screenshot = UploadObjectSingleOperation.saveImageToS3ForUser(img, (new URL(browser.getDriver().getCurrentUrl())).getHost(), checksum, BrowserType.create(browser.getBrowserName()), user_id);

				img.flush();
			}
			*/
			input_tag.getRules().addAll(extractor.extractInputRules(input_tag));
			input_tag = element_service.saveFormElement(input_tag);
			log.warn("rules applied to input tag   ::   "+input_tag.getRules().size());

			elements.add(input_tag);
		}
		
		return elements;
	}
	
	/**
	 * Extracts elements from a page
	 * 
	 * @param page_src {@link String} representing the source of the page
	 * @param url {@link URL} representing the url of the page
	 * @param rule_sets {@link List} of {@link RuleSet}s to extract elements from
	 * @return {@link List} of {@link com.looksee.models.Element}s representing the elements
	 * 
	 * precondition: page_src != null
	 * precondition: url != null
	 * precondition: rule_sets != null
	 * 
	 * @throws IOException if an error occurs while extracting elements
	 * @throws XPathExpressionException if an error occurs while extracting elements
	 */
	public List<com.looksee.models.Element> extractElements(String page_src, URL url, List<RuleSet> rule_sets) throws IOException, XPathExpressionException {
		assert page_src != null;
		assert url != null;
		assert rule_sets != null;

		return getDomElements(page_src, url, rule_sets);
	}
	
	/**
	 * Extracts elements from a page
	 * 
	 * @param page_source {@link String} representing the source of the page
	 * @param url {@link URL} representing the url of the page
	 * @param rule_sets {@link List} of {@link RuleSet}s to extract elements from
	 * @param reviewed_xpaths {@link Set} of {@link String}s representing the xpaths of the elements to review
	 * @return {@link List} of {@link com.looksee.models.Element}s representing the elements
	 * 
	 * precondition: page_source != null
	 * precondition: url != null
	 * precondition: rule_sets != null
	 */
	private synchronized List<com.looksee.models.Element> getDomElements(String page_source, URL url, List<RuleSet> rule_sets) throws IOException, XPathExpressionException {
		assert page_source != null;
		assert !page_source.isEmpty();
		assert url != null;
		assert rule_sets != null;
		
		List<com.looksee.models.Element> visited_elements = new ArrayList<>();
		Map<String, String> frontier = new HashMap<>();
		Map<String, Integer> xpath_cnt = new HashMap<>();
		
		//get html doc and get root element
		Document html_doc = Jsoup.parse(page_source);
		Element root = html_doc.getElementsByTag("body").get(0);
		
		//create element state from root node
		Map<String, String> attributes = generateAttributesMapUsingJsoup(root);
		log.warn("page source 1 :: "+page_source.length());
		log.warn("url 1  :: "+url);
		Map<String, String> css_props = new HashMap<>();
		try{
			css_props.putAll(CssUtils.loadCssPrerenderedPropertiesUsingParser(rule_sets, root));
		}
		catch(Exception e) {
			log.warn(e.getMessage());
		}
		
		com.looksee.models.Element root_element = buildElement("//body", attributes, root, ElementClassification.ANCESTOR, css_props );
		root_element = element_service.save(root_element);

		//put element on frontier
		frontier.put("//body",root_element.getKey());
		while(!frontier.isEmpty()) {
			String next_xpath = frontier.keySet().iterator().next();
			String parent_element_key = frontier.remove(next_xpath);
			//ElementState root_element = frontier.remove(next_xpath);
			//visited_elements.add(root_element);
			//get Element by xpath
			
			Elements elements = Xsoup.compile(next_xpath).evaluate(html_doc).getElements();
			if(elements.size() == 0) {
				log.warn("NO ELEMENTS WITH XPATH FOUND :: "+next_xpath + "   :     url :   "  + url.toString());
			}
			Element element = elements.first();
			//get child elements for element
			attributes = generateAttributesMapUsingJsoup(element);
			
			Map<String, String> pre_render_css_props = new HashMap<>();
			
			try{
				pre_render_css_props.putAll(CssUtils.loadCssPrerenderedPropertiesUsingParser(rule_sets, element));
			}
			catch(Exception e) {
				log.warn(e.getMessage());
			}
			
			ElementClassification classification = null;
			List<Element> children = new ArrayList<Element>(element.children());
			if(children.isEmpty()) {
				classification = ElementClassification.LEAF;
			}
			else if(isSliderElement(element)) {
				classification = ElementClassification.SLIDER;
			}
			else {
				classification = ElementClassification.ANCESTOR;
			}
			
			com.looksee.models.Element element_state = buildElement(next_xpath, attributes, element, classification, pre_render_css_props);
			
			element_state = element_service.save(element_state);
			visited_elements.add(element_state);
			element_service.addChildElement(parent_element_key, element_state.getKey());
			
			
			for(Element child : children) {
				if(isStructureTag(child.tagName())) {
					continue;
				}
				String xpath = next_xpath + "/" + child.tagName();
				
				if(xpath_cnt.containsKey(xpath)) {
					xpath_cnt.put(xpath, xpath_cnt.get(xpath)+1);
				}
				else {
					xpath_cnt.put(xpath, 1);
				}
				
				xpath = xpath + "["+xpath_cnt.get(xpath)+"]";

				frontier.put(xpath, element_state.getKey());
			}
		}
		return visited_elements;
	}
}