package edu.ucsb.nceas.mdqengine.scheduler;

import edu.ucsb.nceas.mdqengine.MDQconfig;
import edu.ucsb.nceas.mdqengine.DataONE;
import edu.ucsb.nceas.mdqengine.exception.MetadigStoreException;
import edu.ucsb.nceas.mdqengine.model.Run;
import edu.ucsb.nceas.mdqengine.model.Task;
import edu.ucsb.nceas.mdqengine.store.DatabaseStore;
import edu.ucsb.nceas.mdqengine.store.MDQStore;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.dataone.client.rest.HttpMultipartRestClient;
import org.dataone.client.rest.MultipartRestClient;
import org.dataone.client.v2.impl.MultipartCNode;
import org.dataone.client.v2.impl.MultipartMNode;
import org.dataone.mimemultipart.SimpleMultipartEntity;
import org.dataone.service.exceptions.NotAuthorized;
import org.dataone.service.types.v1.*;
import org.dataone.service.types.v2.SystemMetadata;
import org.dataone.service.util.TypeMarshaller;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.quartz.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Run a MetaDIG Quality Engine Scheduler task, for example,
 * query a member node for new pids and request that a quality
 * report is created for each one.
 * </p>
 *
 * @author Peter Slaughter
 */
@PersistJobDataAfterExecution
@DisallowConcurrentExecution
public class RequestReportJob implements Job {

    private Log log = LogFactory.getLog(RequestReportJob.class);

    class ListResult {
        // The total result count returned from DataONE
        Integer totalResultCount;
        // The filtered result count returned from DataONE.
        // The DataONE listObjects service returns all new pids for all formatIds
        // but we are typically only interested in a subset of those, i.e. EML metadata pids,
        // so this is the count of pids from the result that we are actually interested in.
        Integer filteredResultCount;
        ArrayList<String> result = new ArrayList<>();

        void setResult(ArrayList result) {
            this.result = result;
        }

        ArrayList getResult() {
            return this.result;
        }

        void setTotalResultCount(Integer count) {
            this.totalResultCount = count;
        }
        void setFilteredResultCount(Integer count) {
            this.filteredResultCount = count;
        }

        Integer getTotalResultCount() {
            return this.totalResultCount;
        }

        Integer getFilteredResultCount() {
            return this.filteredResultCount;
        }
    }

    // Since Quartz will re-instantiate a class every time it
    // gets executed, members non-static member variables can
    // not be used to maintain state!

    /**
     * <p>
     * Empty constructor for job initialization
     * </p>
     * <p>
     * Quartz requires a public empty constructor so that the
     * scheduler can instantiate the class whenever it needs.
     * </p>
     */
    public RequestReportJob() {
    }

    /**
     * <p>
     * Called by the <code>{@link org.quartz.Scheduler}</code> when a
     * <code>{@link org.quartz.Trigger}</code> fires that is associated with
     * the <code>Job</code>.
     * </p>
     *
     * @throws JobExecutionException if there is an exception while executing the job.
     */
    public void execute(JobExecutionContext context)
            throws JobExecutionException {

        String qualityServiceUrl = null;

        //Log log = LogFactory.getLog(RequestReportJob.class);
        JobKey key = context.getJobDetail().getKey();
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();

        String taskName = dataMap.getString("taskName");
        log.debug("taskName: " + taskName);
        String taskType = dataMap.getString("taskType");
        log.debug("taskType: " + taskType);
        String pidFilter = dataMap.getString("pidFilter");
        log.debug("pidFilter: " + pidFilter);
        String suiteId = dataMap.getString("suiteId");
        log.debug("suiteId: " + suiteId);
        String nodeId = dataMap.getString("nodeId");
        log.debug("nodeId: " + nodeId);
        String startHarvestDatetimeStr = dataMap.getString("startHarvestDatetime");
        log.debug("startHavestDatetimeStr: " + startHarvestDatetimeStr);
        int harvestDatetimeInc = dataMap.getInt("harvestDatetimeInc");
        log.debug("harvestDatetimeInc: " + harvestDatetimeInc);
        int countRequested = dataMap.getInt("countRequested");
        log.debug("countRequested: " + countRequested);
        MultipartRestClient mrc = null;
        MultipartMNode mnNode = null;
        MultipartCNode cnNode = null;

        String authToken = null;
        String subjectId = null;
        String nodeServiceUrl = null;

        try {
            MDQconfig cfg = new MDQconfig();
            qualityServiceUrl = cfg.getString("quality.serviceUrl");

            String nodeAbbr = nodeId.replace("urn:node:", "");
            authToken = cfg.getString(nodeAbbr + ".authToken");
            subjectId = cfg.getString(nodeAbbr + ".subjectId");
            // TODO:  Cache the node values from the CN listNode service
            nodeServiceUrl = cfg.getString(nodeAbbr + ".serviceUrl");
        } catch (ConfigurationException | IOException ce) {
            JobExecutionException jee = new JobExecutionException("Error executing task.");
            jee.initCause(ce);
            throw jee;
        }

        log.debug("Executing task for node: " + nodeId + ", suiteId: " + suiteId);

        try {
            mrc = new HttpMultipartRestClient();
        } catch (Exception e) {
            log.error("Error creating rest client: " + e.getMessage());
            JobExecutionException jee = new JobExecutionException(e);
            jee.setRefireImmediately(false);
            throw jee;
        }

        Session session = DataONE.getSession(subjectId, authToken);

        // Don't know node type yet from the id, so have to manually check if it's a CN
        Boolean isCN = isCN(nodeServiceUrl);
        if(isCN) {
            cnNode = new MultipartCNode(mrc, nodeServiceUrl, session);
        } else {
            mnNode = new MultipartMNode(mrc, nodeServiceUrl, session);
        }

        // Don't know node type yet from the id, so have to manually check if it's a CN
        MDQStore store = null;

        try {
            store = new DatabaseStore();
        } catch (Exception e) {
            e.printStackTrace();
            throw new JobExecutionException("Cannot create store, unable to schedule job", e);
        }

        if(!store.isAvailable()) {
            try {
                store.renew();
            } catch (MetadigStoreException e) {
                e.printStackTrace();
                throw new JobExecutionException("Cannot renew store, unable to schedule job", e);
            }
        }

        // Set UTC as the default time zone for all DateTime operations.
        // Get current datetime, which may be used for start time range.
        DateTimeZone.setDefault(DateTimeZone.UTC);
        DateTime currentDT = new DateTime(DateTimeZone.UTC);
        DateTimeFormatter dtfOut = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SS'Z'");
        String currentDatetimeStr = dtfOut.print(currentDT);

        DateTime startDateTimeRange = null;
        DateTime endDateTimeRange = null;

        String lastHarvestDateStr = null;
        //edu.ucsb.nceas.mdqengine.model.Node node;
        //node = store.getNode(nodeId, jobName);

        Task task;
        task = store.getTask(taskName, taskType);
        // If a 'task' entry has not been saved for this task name yet, then a 'lastHarvested'
        // DataTime will not be available, in which case the 'startHarvestDataTime' from the
        // config file will be used.
        if(task.getLastHarvestDatetime() == null) {
            task = new Task();
            task.setTaskName(taskName);
            task.setTaskType(taskType);
            lastHarvestDateStr = startHarvestDatetimeStr;
            task.setLastHarvestDatetime(lastHarvestDateStr);
        } else {
            lastHarvestDateStr = task.getLastHarvestDatetime();
        }

        DateTime lastHarvestDate = new DateTime(lastHarvestDateStr);
        // Set the search start datetime to the last harvest datetime, unless it is in the
        // future. (This can happen when the previous time range end was for the current day,
        // as the end datetime range for the previous task run will have been stored as the
        // new lastharvestDateTime.
        DateTime startDTR = null;
        if(lastHarvestDate.isAfter(currentDT.toInstant())) {
            startDTR = currentDT;
        } else {
            startDTR = new DateTime(lastHarvestDate);
        }

        DateTime endDTR = new DateTime(startDTR);
        endDTR = endDTR.plusDays(harvestDatetimeInc);
        if(endDTR.isAfter(currentDT.toInstant())) {
            endDTR = currentDT;
        }

        // If the start and end harvest dates are the same (happends for a new node), then
        // tweek the start so that DataONE listObjects doesn't complain.
        if(startDTR == endDTR ) {
            startDTR = startDTR.minusMinutes(1);
        }

        String startDTRstr = dtfOut.print(startDTR);
        String endDTRstr = dtfOut.print(endDTR);

        Integer startCount = new Integer(0);
        ListResult result = null;
        Integer totalResultCount = null;
        Integer filteredResultCount = null;

        boolean morePids = true;
        while(morePids) {
            ArrayList<String> pidsToProcess = null;
            log.info("Getting pids for node: " + nodeId + ", suiteId: " + suiteId + ", harvest start: " + startDTRstr);

            try {
                result = getPidsToProcess(cnNode, mnNode, isCN, session, suiteId, nodeId, pidFilter, startDTRstr, endDTRstr, startCount, countRequested);
                pidsToProcess = result.getResult();
                totalResultCount = result.getTotalResultCount();
                filteredResultCount = result.getFilteredResultCount();
            } catch (Exception e) {
                JobExecutionException jee = new JobExecutionException("Unable to get pids to process", e);
                jee.setRefireImmediately(false);
                throw jee;
            }

            log.info("Found " + filteredResultCount + " pids" + " for node: " + nodeId);
            for (String pidStr : pidsToProcess) {
                try {
                    log.info("submitting pid: " + pidStr);
                    submitReportRequest(cnNode, mnNode, isCN, session, qualityServiceUrl, pidStr, suiteId);
                } catch (org.dataone.service.exceptions.NotFound nfe) {
                    log.error("Unable to process pid: " + pidStr +  nfe.getMessage());
                    continue;
                } catch (Exception e) {
                    log.error("Unable to process pid:  " + pidStr + " - " + e.getMessage());
                    continue;
                    //JobExecutionException jee = new JobExecutionException("Unable to submit request to create new quality reports", e);
                    //jee.setRefireImmediately(false);
                    //throw jee;
                }
            }

            task.setLastHarvestDatetime(endDTRstr);
            log.debug("taskName: " + task.getTaskName());
            log.debug("taskType: " + task.getTaskType());
            log.debug("lastharvestdate: " + task.getLastHarvestDatetime());
            try {
                store.saveTask(task);
            } catch (MetadigStoreException mse) {
                log.error("Error saving task: " + task.getTaskName());
                JobExecutionException jee = new JobExecutionException("Unable to save new harvest date", mse);
                jee.setRefireImmediately(false);
                throw jee;
            }

            // Check if DataONE returned the max number of results. If so, we have to request more by paging through
            // the results returned pidsToProcess (i.e. DataONE listObjects service).
            if(totalResultCount >= countRequested) {
                morePids = true;
                startCount = startCount + totalResultCount;
                log.info("Paging through more results, current start is " + startCount);
            } else {
                morePids = false;
            }
        }
        store.shutdown();
    }

    public ListResult getPidsToProcess(MultipartCNode cnNode, MultipartMNode mnNode, Boolean isCN, Session session,
                                  String suiteId, String nodeId, String pidFilter, String startHarvestDatetimeStr,
                                  String endHarvestDatetimeStr, int startCount,
                                  int countRequested) throws Exception {

        ArrayList<String> pids = new ArrayList<String>();
        InputStream qis = null;
        ObjectList objList = null;

        ObjectFormatIdentifier formatId = null;
        NodeReference nodeRef = null;
        //nodeRef.setValue(nodeId);
        Identifier identifier = null;
        Boolean replicaStatus = false;

        // Do some back-flips to convert the start and end date to the ancient Java 'Date' type that is
        // used by DataONE 'listObjects()'.
        ZonedDateTime zdt = ZonedDateTime.parse(startHarvestDatetimeStr);
        // start date milliseconds since the epoch date "midnight, January 1, 1970 UTC"
        long msSinceEpoch = zdt.toInstant().toEpochMilli();
        Date startDate = new Date(msSinceEpoch);

        zdt = ZonedDateTime.parse(endHarvestDatetimeStr);
        msSinceEpoch = zdt.toInstant().toEpochMilli();
        Date endDate = new Date(msSinceEpoch);

        try {
            // Even though MultipartMNode and MultipartCNode have the same parent class, their interfaces are differnt, so polymorphism
            // isn't happening here.
            if(isCN) {
                objList = cnNode.listObjects(session, startDate, endDate, formatId, nodeRef, identifier, startCount, countRequested);
            } else {
                objList = mnNode.listObjects(session, startDate, endDate, formatId, identifier, replicaStatus, startCount, countRequested);
            }
            //log.info("Got " + objList.getCount() + " pids for format: " + formatId.getValue() + " pids.");
        } catch (Exception e) {
            log.error("Error retrieving pids for node " + nodeId + ": " + e.getMessage());
            throw e;
        }

        String thisFormatId = null;
        String thisPid = null;
        int pidCount = 0;

        if (objList.getCount() > 0) {
            for(ObjectInfo oi: objList.getObjectInfoList()) {
                thisFormatId = oi.getFormatId().getValue();
                thisPid = oi.getIdentifier().getValue();
                log.debug("Checking pid: " + thisPid + ", format: " + thisFormatId);

                // Check all pid filters. There could be multiple wildcard filters, which are separated
                // by ','.
                String [] filters = pidFilter.split("\\|");
                Boolean found = false;
                for(String thisFilter:filters) {
                    if(thisFormatId.matches(thisFilter)) {
                        found = true;
                        continue;
                    }
                }

                // Always re-create a report, even if it exists for a pid, as the sysmeta could have
                // been updated (i.e. obsoletedBy, access) and the quality report and index contain
                // sysmeta fields.
                if(found) {
                //    if (!runExists(thisPid, suiteId, store)) {
                    pidCount = pidCount++;
                    pids.add(thisPid);
                    log.info("adding pid " + thisPid + ", formatId: " + thisFormatId);
                //    }
                }
            }
        }

        ListResult result = new ListResult();
        // Set the count for the number of desired pids filtered from the total result set
        result.setFilteredResultCount(pidCount);
        // Set the count for the total number of pids returned from DataONE (all formatIds) for this query
        result.setTotalResultCount(objList.getCount());
        result.setResult(pids);

        return result;
    }

    public boolean runExists(String pid, String suiteId, MDQStore store) throws MetadigStoreException {

        boolean found = false;

        if(!store.isAvailable()) {
            try {
                store.renew();
            } catch (MetadigStoreException e) {
                e.printStackTrace();
                throw e;
            }
        }

        Run run = store.getRun(pid, suiteId);
        if(run != null) {
            found = true;
        } else {
            found = false;
        }

        return found;
    }

    public void submitReportRequest(MultipartCNode cnNode, MultipartMNode mnNode, Boolean isCN,  Session session, String qualityServiceUrl, String pidStr, String suiteId) throws Exception {

        SystemMetadata sysmeta = null;
        InputStream objectIS = null;
        InputStream runResultIS = null;

        Identifier pid = new Identifier();
        pid.setValue(pidStr);

        try {
            if (isCN) {
                sysmeta = cnNode.getSystemMetadata(session, pid);
            } else {
                sysmeta = mnNode.getSystemMetadata(session, pid);
            }
        } catch (NotAuthorized na) {
            log.error("Not authorized to read sysmeta for pid: " + pid.getValue() + ", continuing with next pid...");
            return;
        } catch (Exception e) {
            throw(e);
        }

        try {
            if(isCN) {
                objectIS = cnNode.get(session, pid);
            } else  {
                objectIS = mnNode.get(session, pid);
            }
            log.debug("Retrieved metadata object for pid: " + pidStr);
        } catch (NotAuthorized na) {
            log.error("Not authorized to read pid: " + pid + ", continuing with next pid...");
            return;
        } catch (Exception e) {
            throw(e);
        }

        // quality suite service url, i.e. "http://docke-ucsb-1.dataone.org:30433/quality/suites/knb.suite.1/run
        qualityServiceUrl = qualityServiceUrl + "/suites/" + suiteId + "/run";
        HttpPost post = new HttpPost(qualityServiceUrl);

        try {
            // add document
            SimpleMultipartEntity entity = new SimpleMultipartEntity();
            entity.addFilePart("document", objectIS);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            TypeMarshaller.marshalTypeToOutputStream(sysmeta, baos);
            entity.addFilePart("systemMetadata", new ByteArrayInputStream(baos.toByteArray()));

            // make sure we get XML back
            post.addHeader("Accept", "application/xml");

            // send to service
            log.trace("submitting: " + qualityServiceUrl);
            post.setEntity((HttpEntity) entity);
            CloseableHttpClient client = HttpClients.createDefault();
            CloseableHttpResponse response = client.execute(post);

            // retrieve results
            HttpEntity reponseEntity = response.getEntity();
            if (reponseEntity != null) {
                runResultIS = reponseEntity.getContent();
            }
        } catch (Exception e) {
            throw(e);
        }
    }

    private Boolean isCN(String serviceUrl) {

        Boolean isCN = false;
        // Identity node as either a CN or MN based on the serviceUrl
        String pattern = "https*://cn.*?\\.dataone\\.org|https*://cn.*?\\.test\\.dataone\\.org";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(serviceUrl);
        if (m.find()) {
            isCN = true;
            log.debug("service URL is for a CN: " + serviceUrl);
        } else {
            log.debug("service URL is not for a CN: " + serviceUrl);
            isCN = false;
        }

        return isCN;
    }
}
