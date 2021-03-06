package org.ihtsdo.termserver.scripting.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.ihtsdo.otf.rest.client.ExpressiveErrorHandler;
import org.ihtsdo.otf.rest.client.Status;
import org.ihtsdo.otf.rest.client.authoringservices.RestyOverrideAccept;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Classification;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.*;

import us.monoid.json.*;
import us.monoid.web.*;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class TermServerClient {
	
	public enum ExtractType {
		DELTA, SNAPSHOT, FULL;
	};

	public enum ExportType {
		PUBLISHED, UNPUBLISHED, MIXED;
	}
	
	public static SimpleDateFormat YYYYMMDD = new SimpleDateFormat("yyyyMMdd");
	public static final int MAX_TRIES = 3;
	public static final int retry = 15;
	public static final int MAX_PAGE_SIZE = 10000; 
	
	protected static Gson gson;
	static {
		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.setPrettyPrinting();
		gsonBuilder.registerTypeAdapter(Relationship.class, new RelationshipSerializer());
		gsonBuilder.excludeFieldsWithoutExposeAnnotation();
		gson = gsonBuilder.create();
	}
	
	private final Resty resty;
	private final RestTemplate restTemplate;
	private final HttpHeaders headers;
	private final String url;
	private static final String ALL_CONTENT_TYPE = "*/*";
	private static final String SNOWOWL_CONTENT_TYPE = "application/json";
	private final Set<SnowOwlClientEventListener> eventListeners;
	private Logger logger = LoggerFactory.getLogger(getClass());
	public static boolean supportsIncludeUnpublished = true;

	public TermServerClient(String serverUrl, String cookie) {
		this.url = serverUrl;
		eventListeners = new HashSet<>();
		resty = new Resty(new RestyOverrideAccept(ALL_CONTENT_TYPE));
		resty.withHeader("Cookie", cookie);
		resty.authenticate(this.url, null,null);
		
		headers = new HttpHeaders();
		headers.add("Cookie", cookie);
		headers.add("Accept", SNOWOWL_CONTENT_TYPE);
		
		restTemplate = new RestTemplateBuilder()
				.rootUri(this.url)
				.additionalMessageConverters(new GsonHttpMessageConverter(gson))
				.errorHandler(new ExpressiveErrorHandler())
				.build();
		
		//Add a ClientHttpRequestInterceptor to the RestTemplate
		restTemplate.getInterceptors().add(new ClientHttpRequestInterceptor(){
			@Override
			public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
				request.getHeaders().addAll(headers);
				return execution.execute(request, body);
			}
		}); 
	}
	
	public Branch getBranch(String branchPath) throws TermServerScriptException {
		int retry = 0;
		while (retry < 3) {
			try {
				String url = getBranchesPath(branchPath);
				logger.debug("Recovering branch information from " + url);
				return restTemplate.getForObject(url, Branch.class);
			} catch (RestClientException e) {
				if (++retry < 3) {
					System.err.println("Problem recovering branch information.   Retrying after a nap...");
					try { Thread.sleep(1000 * 5); } catch (Exception i) {}
				} else {
					throw new TermServerScriptException(translateRestClientException(e));
				}
			}
		}
		return null;
	}
	
	public List<Branch> getBranchChildren(String branchPath) throws TermServerScriptException {
		try {
			String url = getBranchesPath(branchPath) + "/children?immediateChildren=true";
			logger.debug("Recovering branch child information from " + url);
			ResponseEntity<List<Branch>> response = restTemplate.exchange(
					url,
					HttpMethod.GET,
					null,
					new ParameterizedTypeReference<List<Branch>>(){});
			return response.getBody();
		} catch (RestClientException e) {
			throw new TermServerScriptException(translateRestClientException(e));
		}
	}
	
	public List<CodeSystemVersion> getCodeSystemVersions() throws TermServerScriptException {
		try {
			String url = this.url + "/codesystems/SNOMEDCT/versions?showFutureVersions=true";
			logger.debug("Recovering codesystem versions from " + url);
			return restTemplate.getForObject(url, CodeSystemCollection.class).getItems();
		} catch (RestClientException e) {
			throw new TermServerScriptException(translateRestClientException(e));
		}
	}

	private Throwable translateRestClientException(RestClientException e) {
		//Can we find some known error in the text returned so that we don't have
		//to return the whole thing?
		String message = e.getMessage();
		String mainContent = StringUtils.substringBetween(message, "<section class=\"mainContent contain\">", "</section>");
		if (mainContent != null) {
			return new TermServerScriptException("Returned from TS: " + mainContent);
		}
		return e;
	}

	public Concept createConcept(Concept c, String branchPath) throws TermServerScriptException {
		try {
			Concept newConcept =  restTemplate.postForObject(getConceptBrowserPath(branchPath), new HttpEntity<>(c, headers), Concept.class);
			logger.info("Created concept: " + newConcept + " with " + c.getDescriptions().size() + " descriptions.");
			return newConcept;
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	public Concept updateConcept(Concept c, String branchPath) throws TermServerScriptException {
		try {
			Preconditions.checkNotNull(c.getConceptId());
			boolean updatedOK = false;
			int tries = 0;
			while (!updatedOK) {
				try {
					ResponseEntity<Concept> response = restTemplate.exchange(
							getConceptBrowserPath(branchPath) + "/" + c.getConceptId(),
							HttpMethod.PUT,
							new HttpEntity<>(c, headers),
							Concept.class);
					c = response.getBody();
					updatedOK = true;
					logger.info("Updated concept " + c.getConceptId());
				} catch (Exception e) {
					tries++;
					if (tries >= MAX_TRIES) {
						throw new TermServerScriptException("Failed to update concept: " + c + " after " + tries + " attempts due to " + e.getMessage(), e);
					}
					logger.debug("Update of concept failed, trying again....",e);
					Thread.sleep(30*1000); //Give the server 30 seconds to recover
				}
			}
			return c;
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	public Concept getConcept(String sctid, String branchPath) throws TermServerScriptException {
			String url = getConceptBrowserPath(branchPath) + "/" + sctid;
			return restTemplate.getForObject(url, Concept.class);
	}

	public void deleteConcept(String sctId, String branchPath) throws TermServerScriptException {
		try {
			restTemplate.delete(getConceptsPath(sctId, branchPath));
			logger.info("Deleted concept " + sctId + " from " + branchPath);
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to delete concept: " + sctId, e);
		}
	}
	
	public ConceptCollection getConcepts(String ecl, String branchPath, String searchAfter, int limit) throws TermServerScriptException {
		String criteria = "ecl=" + SnomedUtils.makeMachineReadable(ecl);
		return getConceptsMatchingCriteria(criteria, branchPath, searchAfter, limit);
	}
	
	public ConceptCollection getConceptsMatchingCriteria(String criteria, String branchPath, String searchAfter, int limit) throws TermServerScriptException {
		String url = getConceptsPath(branchPath) + "?active=true&limit=" + limit;
		if (!StringUtils.isEmpty(searchAfter)) {
			url += "&searchAfter=" + searchAfter;
		}
		//RestTemplate will attempt to expand out any curly braces, and we can't URLEncode
		//because RestTemplate does that for us.  So use curly braces to substitute in our criteria
		url += "&" + criteria;
		System.out.println("Calling: " + url);
		return restTemplate.getForObject(url, ConceptCollection.class);
	}
	
	public int getConceptsCount(String ecl, String branchPath) throws TermServerScriptException {
		String url = getConceptsPath(branchPath) + "?active=true&limit=1";
		ecl = SnomedUtils.makeMachineReadable(ecl);
		//RestTemplate will attempt to expand out any curly braces, and we can't URLEncode
		//because RestTemplate does that for us.  So use curly braces to substitute in our ecl
		url += "&ecl={ecl}";
		System.out.println("Calling " + url + " with ecl parameter: " + ecl);
		return restTemplate.getForObject(url, ConceptCollection.class, ecl).getTotal();
	}

	private String getBranchesPath(String branchPath) {
		return  url + "/branches/" + branchPath;
	}
	
	private String getConceptsPath(String sctId, String branchPath) {
		return url + "/" + branchPath + "/concepts/" + sctId;
	}

	private String getConceptsPath(String branchPath) {
		return url + "/" + branchPath + "/concepts";
	}

	private String getConceptBrowserPath(String branchPath) {
		return url + "/browser/" + branchPath + "/concepts";
	}
	
	private String getConceptBrowserValidationPath(String branchPath) {
		return url + "/browser/" + branchPath + "/validate/concept";
	}
	
	private String getDescriptionsPath(String branchPath, String id) {
		return url  + "/" + branchPath + "/descriptions/" + id;
	}

	public String createBranch(String parent, String branchName) throws TermServerScriptException {
		try {
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("parent", parent);
			jsonObject.put("name", branchName);
			resty.json(url + "/branches", RestyHelper.content(jsonObject, SNOWOWL_CONTENT_TYPE));
			final String branchPath = parent + "/" + branchName;
			logger.info("Created branch {}", branchPath);
			for (SnowOwlClientEventListener eventListener : eventListeners) {
				eventListener.branchCreated(branchPath);
			}
			return branchPath;
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	public void mergeBranch(String source, String target) throws TermServerScriptException {
		try {
			final JSONObject json = new JSONObject();
			json.put("source", source);
			json.put("target", target);
			final String message = "Merging " + source + " to " + target;
			json.put("commitComment", message);
			logger.info(message);
			resty.json(url + "/merges", RestyHelper.content(json, SNOWOWL_CONTENT_TYPE));
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	public void deleteBranch(String branchPath) throws TermServerScriptException {
		try {
			resty.json(url + "/branches/" + branchPath, Resty.delete());
			logger.info("Deleted branch {}", branchPath);
		} catch (IOException e) {
			throw new TermServerScriptException(e);
		}
	}

	public JSONResource search(String query, String branchPath) throws TermServerScriptException {
		try {
			return resty.json(url + "/browser/" + branchPath + "/descriptions?query=" + query);
		} catch (IOException e) {
			throw new TermServerScriptException(e);
		}
	}

	public JSONResource searchWithPT(String query, String branchPath) throws TermServerScriptException {
		try {
			return resty.json(url + "/browser/" + branchPath + "/descriptions-pt?query=" + query);
		} catch (IOException e) {
			throw new TermServerScriptException(e);
		}
	}

	public void addEventListener(SnowOwlClientEventListener eventListener) {
		eventListeners.add(eventListener);
	}

	/**
	 * Returns id of classification
	 * @param branchPath
	 * @return
	 * @throws IOException
	 * @throws JSONException
	 * @throws InterruptedException
	 */
	public String classifyAndWaitForComplete(String branchPath) throws TermServerScriptException {
		try {
			final JSONObject json = new JSONObject();
			json.put("reasonerId", "org.semanticweb.elk.elk.reasoner.factory");
			String url = this.url + "/" + branchPath + "/classifications";
			System.out.println(url);
			System.out.println(json.toString(3));
			final JSONResource resource = resty.json(url, RestyHelper.content(json, SNOWOWL_CONTENT_TYPE));
			final String location = resource.getUrlConnection().getHeaderField("Location");
			System.out.println("location " + location);

			String status;
			do {
				final JSONObject jsonObject = resty.json(location).toObject();
				status = jsonObject.getString("status");
			} while (("SCHEDULED".equals(status) || "RUNNING".equals(status) && sleep(10)));

			if ("COMPLETED".equals(status)) {
				return location.substring(location.lastIndexOf("/"));
			} else {
				throw new TermServerScriptException("Unexpected classification state " + status);
			}
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	private boolean sleep(int seconds) throws InterruptedException {
		Thread.sleep(1000 * seconds);
		return true;
	}

	public Resty getResty() {
		return resty;
	}

	public String getUrl() {
		return url;
	}

	public JSONArray getMergeReviewDetails(String mergeReviewId) throws TermServerScriptException {
		logger.info("Getting merge review {}", mergeReviewId);
		try {
			return resty.json(getMergeReviewUrl(mergeReviewId) + "/details").array();
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	private String getMergeReviewUrl(String mergeReviewId) {
		return this.url + "/merge-reviews/" + mergeReviewId;
	}

	public void saveConceptMerge(String mergeReviewId, JSONObject mergedConcept) throws TermServerScriptException {
		try {
			String id = ConceptHelper.getConceptId(mergedConcept);
			logger.info("Saving merged concept {} for merge review {}", id, mergeReviewId);
			resty.json(getMergeReviewUrl(mergeReviewId) + "/" + id, RestyHelper.content(mergedConcept, SNOWOWL_CONTENT_TYPE));
		} catch (JSONException | IOException e) {
			throw new TermServerScriptException(e);
		}
	}

	public File export(String branchPath, String effectiveDate, ExportType exportType, ExtractType extractType, File saveLocation)
			throws TermServerScriptException {
		Map<String, Object> exportRequest = prepareExportRequest(branchPath, effectiveDate, exportType, extractType);
		logger.info ("Initiating export with {}", exportRequest);
		String exportLocationURL = initiateExport(exportRequest);
		//INFRA-1489 Workaround
		if (!exportLocationURL.startsWith("https://")) {
			exportLocationURL = exportLocationURL.replace("http:/", "https://");
			System.err.println("Malformed export location received, corrected to https://");
		}
		if (saveLocation == null) {
			try {
				saveLocation = File.createTempFile("ts-extract", ".zip");
			} catch (IOException e) {
				throw new TermServerScriptException(e);
			}
		}
		File recoveredArchive = recoverExportedArchive(exportLocationURL, saveLocation);
		return recoveredArchive;
	}
	
	private Map<String, Object> prepareExportRequest(String branchPath, String effectiveDate, ExportType exportType, ExtractType extractType)
			throws TermServerScriptException {
		Map<String, Object> exportRequest = new HashMap<>();
		exportRequest.put("type", extractType);
		exportRequest.put("branchPath", branchPath);
		switch (exportType) {
			case MIXED:  //Snapshot allows for both published and unpublished, where unpublished
				//content would get the transient effective Date
				if (!extractType.equals(ExtractType.SNAPSHOT)) {
					throw new TermServerScriptException("Export type " + exportType + " not recognised");
				}
				if (supportsIncludeUnpublished) {
					exportRequest.put("includeUnpublished", true);
				}
			case UNPUBLISHED:
				//Now leaving effective date blank if not specified
				if (effectiveDate != null) {
					//String tet = (effectiveDate == null) ?YYYYMMDD.format(new Date()) : effectiveDate;
					exportRequest.put("transientEffectiveTime", effectiveDate);
				}
				break;
			case PUBLISHED:
				if (effectiveDate == null) {
					throw new TermServerScriptException("Cannot export published data without an effective date");
				}
				exportRequest.put("deltaStartEffectiveTime", effectiveDate);
				exportRequest.put("deltaEndEffectiveTime", effectiveDate);
				exportRequest.put("transientEffectiveTime", effectiveDate);
				break;
			
			default:
				throw new TermServerScriptException("Export type " + exportType + " not recognised");
		}
		return exportRequest;
	}

	private String initiateExport(Map<String, Object> exportRequest) throws TermServerScriptException {
		try {
			/*JSONResource jsonResponse = resty.json(url + "/exports", RestyHelper.content(jsonObj, SNOWOWL_CONTENT_TYPE));
			Object exportLocationURLObj = jsonResponse.getUrlConnection().getHeaderField("Location");
			if (exportLocationURLObj == null) {
				String actualResponse = "Unable to parse response";
				try {
					actualResponse = jsonResponse.toObject().toString();
				} catch (Exception e) {}
				throw new TermServerScriptException("Failed to obtain location of export.  Instead received: " + actualResponse);
			} else {
				logger.info ("Export location recovered: {}",exportLocationURLObj.toString());
			}
			return exportLocationURLObj.toString() + "/archive";*/
			
			HttpEntity<?> entity = new HttpEntity<>(exportRequest, headers ); // for request
			HttpEntity<String> response = restTemplate.exchange(url + "/exports", HttpMethod.POST, entity, String.class);
			return response.getHeaders().get("Location").get(0);
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to initiate export", e);
		}
	}
	
	private File recoverExportedArchive(String exportLocationURL,  final File saveLocation) throws TermServerScriptException {
		try {
			logger.info("Recovering exported archive from {}", exportLocationURL);
			logger.info("Saving exported archive to {}", saveLocation);
			RequestCallback requestCallback = request -> request.getHeaders()
					.setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
			restTemplate.execute(exportLocationURL + "/archive", HttpMethod.GET, requestCallback, clientHttpResponse -> {
				StreamUtils.copy(clientHttpResponse.getBody(), new FileOutputStream(saveLocation));
				return null;
			});
			if (saveLocation.length() == 0) {
				throw new RestClientException("Archive recovered as 0 bytes");
			}
			logger.debug("Extract recovery complete");
			return saveLocation;
		} catch (RestClientException e) {
			throw new TermServerScriptException("Unable to recover exported archive from " + exportLocationURL, e);
		}
	}

	public JSONResource updateDescription(String descId, JSONObject descObj, String branchPath) throws TermServerScriptException {
		try {
			Preconditions.checkNotNull(descId);
			JSONResource response =  resty.json(getDescriptionsPath(branchPath,descId) + "/updates", RestyHelper.content(descObj, SNOWOWL_CONTENT_TYPE));
			logger.info("Updated description " + descId);
			return response;
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	public JSONArray getLangRefsetMembers(String descriptionId, String refsetId, String branch) throws TermServerScriptException {
		final String url = this.url + "/" + branch + "/members?referenceSet=" + refsetId + "&referencedComponentId=" + descriptionId;
		try {
			return (JSONArray) resty.json(url).get("items");
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}

	public void deleteRefsetMember(String refsetMemberId, String branch, boolean toForce) throws TermServerScriptException {
			restTemplate.delete(getRefsetMemberUpdateUrl(refsetMemberId, branch, toForce));
			logger.info("Deleted refset member id:" + refsetMemberId);
	}
	
	private String getRefsetMemberUrl(String refSetMemberId, String branch) {
		return this.url + "/" + branch + "/members/" + refSetMemberId;
	}
	
	private String getRefsetMemberUrl(String branch) {
		return this.url + "/" + branch + "/members";
	}
	
	private String getRefsetMemberUpdateUrl(String refSetMemberId, String branch, boolean toForce) {
		return getRefsetMemberUrl(refSetMemberId, branch) + "?force=" + toForce;
	}

	public Refset loadRefsetEntries(String branchPath, String refsetId, String referencedComponentId) throws TermServerScriptException {
		try {
			String endPoint = this.url + "/" + branchPath + "/members?referenceSet=" + refsetId + "&referencedComponentId=" + referencedComponentId;
			JSONResource response = resty.json(endPoint);
			String json = response.toObject().toString();
			Refset refsetObj = gson.fromJson(json, Refset.class);
			return refsetObj;
		} catch (Exception e) {
			throw new TermServerScriptException("Unable to recover refset for " + refsetId + " - " + referencedComponentId, e);
		}
	}

	public void updateRefsetMember(String branchPath, RefsetMember refsetEntry, boolean forceUpdate) throws TermServerScriptException {
		try {
			ResponseEntity<RefsetMember> response = restTemplate.exchange(
					getRefsetMemberUpdateUrl(refsetEntry.getId(), branchPath, forceUpdate),
					HttpMethod.PUT,
					new HttpEntity<>(refsetEntry, headers),
					RefsetMember.class);
			RefsetMember updatedEntry = response.getBody();
			Preconditions.checkNotNull(updatedEntry);
			logger.info("Updated refset member " + refsetEntry.getId());
		} catch (Exception e) {
			throw new TermServerScriptException("Unable to update refset entry " + refsetEntry + " due to " + e.getMessage(), e);
		}
	}
	

	public void waitForCompletion(String branchPath, Classification classification) throws TermServerScriptException {
		try {
			String endPoint = this.url + "/" + branchPath + "/classifications/" + classification.getId();
			Status status = new Status("Unknown");
			long sleptSecs = 0;
			do {
				JSONResource response = resty.json(endPoint);
				String json = response.toObject().toString();
				status = gson.fromJson(json, Status.class);
				if (!status.isFinalState()) {
					Thread.sleep(retry * 1000);
					sleptSecs += retry;
					if (sleptSecs % 60 == 0) {
						System.out.println("Waited for " + sleptSecs + " for classification " + classification.getId() + " on " + branchPath);
					}
				}
			} while (!status.isFinalState());
		} catch (Exception e) {
			throw new TermServerScriptException("Unable to recover status of classification " + classification.getId() + " due to " + e.getMessage(), e);
		}
	}

	public DroolsResponse[] validateConcept(Concept c, String branchPath) {
		String url = getConceptBrowserValidationPath(branchPath);
		HttpEntity<Concept> request = new HttpEntity<>(c, headers); 
		return restTemplate.postForObject(url, request, DroolsResponse[].class);
	}

	public RefsetMember getRefsetMember(String uuid, String branchPath) {
		try {
			String url = getRefsetMemberUrl(uuid, branchPath);
			return restTemplate.getForObject(url, RefsetMember.class);
		} catch (Exception e) {
			if (e.getMessage().contains("Member not found")) {
				return null;
			}
			throw e;
		}
	}
	
	public Collection<RefsetMember> findRefsetMembers(String branchPath, Concept c, String refsetFilter) throws TermServerScriptException {
		return findRefsetMembers(branchPath, Collections.singletonList(c), refsetFilter);
	}
	
	public Collection<RefsetMember> findRefsetMembers(String branchPath, List<Concept> refCompIds, String refsetFilter) throws TermServerScriptException {
		try {
			String url = getRefsetMemberUrl(branchPath);
			url += "?";
			boolean isFirst = true;
			for (Component c : refCompIds) {
				if (!isFirst) {
					url += "&";
				} else {
					isFirst = false;
				}
				url += "referencedComponentId=" + c.getId();
			}
			if (refsetFilter != null) {
				url += "&referenceSet=" + refsetFilter;
			}
			RefsetMemberCollection members = restTemplate.getForObject(url, RefsetMemberCollection.class);
			return members.getItems();
		} catch (Exception e) {
			throw new TermServerScriptException(e);
		}
	}
	
	public RefsetMember updateRefsetMember(RefsetMember rm, String branchPath) {
		String url = getRefsetMemberUrl(rm.getId(), branchPath);
		ResponseEntity<RefsetMember> response = restTemplate.exchange(
				url,
				HttpMethod.PUT,
				new HttpEntity<>(rm, headers),
				RefsetMember.class);
		return response.getBody();
	}

	public CodeSystem getCodeSystem(String codeSystemName) throws TermServerScriptException {
		try {
			String url = this.url + "/codesystems/" + codeSystemName;
			logger.debug("Recovering codesystem from " + url);
			return restTemplate.getForObject(url, CodeSystem.class);
		} catch (RestClientException e) {
			throw new TermServerScriptException(translateRestClientException(e));
		}
	}

	//Watch that this POST operation is not available in read-only instances
	public List<Concept> filterConcepts(ConceptSearchRequest searchRequest, String branchPath) throws TermServerScriptException {
		try {
			String url = this.url + "/" + branchPath + "/concepts/search";
			ConceptCollection collection = restTemplate.postForObject(
								url,
								searchRequest,
								ConceptCollection.class);
			return collection.getItems();
		} catch (RestClientException e) {
			throw new TermServerScriptException(translateRestClientException(e));
		}
	}

	public List<Description> getUnpromotedDescriptions(String branchPath, boolean unpromotedChangesOnly) throws TermServerScriptException {
		try {
			String url = this.url + "/" + branchPath + "/authoring-stats/new-descriptions?unpromotedChangesOnly=" + (unpromotedChangesOnly?"true":"false");
			Description[] descriptions = restTemplate.getForObject(
								url,
								Description[].class);
			return Arrays.asList(descriptions);
		} catch (RestClientException e) {
			throw new TermServerScriptException(translateRestClientException(e));
		}
	}

}
