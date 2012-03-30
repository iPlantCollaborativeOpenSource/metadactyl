package org.iplantc.workflow.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.iplantc.files.service.FileInfoService;
import org.iplantc.persistence.dto.step.TransformationStep;
import org.iplantc.persistence.dto.transformation.Transformation;
import org.iplantc.workflow.WorkflowException;
import org.iplantc.workflow.core.TransformationActivity;
import org.iplantc.workflow.dao.DaoFactory;
import org.iplantc.workflow.data.DataObject;
import org.iplantc.workflow.data.InputOutputMap;
import org.iplantc.workflow.experiment.dto.JobConstructor;
import org.iplantc.workflow.model.Property;
import org.iplantc.workflow.model.PropertyGroup;
import org.iplantc.workflow.model.Template;
import org.iplantc.workflow.user.UserDetails;

/**
 * Formats a submission request for a job that will be executed on Condor. The code in this class was mostly extracted
 * from ExperimentRunner and only minor refactoring work was done.
 */
public class CondorJobRequestFormatter implements JobRequestFormatter {

    private static final int JSON_INDENT = 4;

    private static final String CONDOR_TYPE = "condor";

    private static final Logger LOG = Logger.getLogger(CondorJobRequestFormatter.class);

    private static final List<String> REFERENCE_GENOME_INFO_TYPES = Arrays.asList("ReferenceSequence",
            "ReferenceAnnotation", "ReferenceGenome");

    private static final Pattern FILE_URL_PATTERN = Pattern.compile("^(?:file://|/)");

    private DaoFactory daoFactory;

    private FileInfoService fileInfo;

    private UrlAssembler urlAssembler;

    private UserDetails userDetails;

    private JSONObject experiment;

    private boolean debug;

    public CondorJobRequestFormatter(DaoFactory daoFactory, FileInfoService fileInfo, UrlAssembler urlAssembler,
            UserDetails userDetails, JSONObject experiment) {
        this.daoFactory = daoFactory;
        this.fileInfo = fileInfo;
        this.urlAssembler = urlAssembler;
        this.userDetails = userDetails;
        this.experiment = experiment;
        this.debug = experiment.optBoolean("debug", false);
    }

    @Override
    public JSONObject formatJobRequest() {
        JobConstructor jobConstructor = new JobConstructor("submit", CONDOR_TYPE);
        jobConstructor.setExperimentJson(experiment);

        String analysisId = experiment.getString("analysis_id");
        TransformationActivity analysis = daoFactory.getTransformationActivityDao().findById(analysisId);
        if (analysis == null) {
            throw new WorkflowException("analysis " + analysisId + " not found");
        }

        jobConstructor.setAnalysis(analysis);

        long workspaceId = Long.parseLong(experiment.getString("workspace_id"));
        LOG.debug("Workspace Id: " + workspaceId);

        jobConstructor.setUsername(userDetails.getShortUsername());

        JSONObject job = jobConstructor.getJob().toJson();

        job.put("email", userDetails.getEmail());

        // this Hash Table is used when resolving the mappings between steps
        HashMap<String, JSONObject> stepMap = new HashMap<String, JSONObject>();

        JSONObject config = experiment.getJSONObject("config");

        List<TransformationStep> steps = analysis.getSteps();

        JSONArray stepArray = new JSONArray();

        for (TransformationStep currentStep : steps) {
            JSONObject step1 = new JSONObject();

            step1.put("name", currentStep.getName());
            step1.put("type", CONDOR_TYPE);
            stepMap.put(step1.getString("name"), step1);

            Transformation transformation = currentStep.getTransformation();

            Template template = daoFactory.getTemplateDao().findById(transformation.getTemplate_id());

            JSONObject finalConfig = new JSONObject();

            @SuppressWarnings("unchecked")
            Set<String> keyset = config.keySet();

            JSONArray jinputs = new JSONArray();
            JSONArray params = new JSONArray();

            // Format inputs and properties for inputs that are not referenced by other properties.
            formatInputs(template, currentStep, keyset, config, jinputs);
            formatUnreferencedInputProperties(template, currentStep, keyset, config, params, transformation,
                    analysis, stepArray);

            // Format the properties.
            formatProperties(analysis, template, currentStep, transformation, params, keyset, config, jinputs,
                    stepArray);

            // Format outputs and properties for outputs taht are not referenced by other properties.
            JSONArray outputs_section = new JSONArray();
            formatOutputs(template, outputs_section);
            formatUnreferencedOutputProperties(template, transformation, params);

            finalConfig.put("input", jinputs);
            finalConfig.put("params", params);
            finalConfig.put("output", outputs_section);
            step1.put("config", finalConfig);

            /**
             * retrieve component for template *
             */
            String componentId = template.getComponent();
            step1.put("component", new DeployedComponentFormatter(daoFactory).formatComponent(componentId));

            /**
             * assemble the job JSON request *
             */
            stepArray.add(step1);

        }

        job.put("steps", stepArray);
        LOG.debug("Job: " + job);
        return job;
    }

    private void formatUnreferencedOutputProperties(Template template, Transformation transformation, JSONArray params) {
        for (DataObject outputObject : template.findUnreferencedOutputs()) {
            JSONObject out = new JSONObject();
            int order = getDataObjectOrder(outputObject);
            if (order < 0) {
                continue;
            }

            JSONObject param = new JSONObject();

            param.put("name", outputObject.getSwitchString());

            if (transformation.containsProperty(outputObject.getId())) {
                param.put("value", transformation.getValueForProperty(outputObject.getId()));
            }
            else {
                param.put("value", outputObject.getName());
            }

            param.put("order", order);
            param.put("id", outputObject.getId());
            params.add(param);
        }
    }

    private void formatOutputs(Template template, JSONArray outputs_section) {
        formatDefinedOutputs(template, outputs_section);
        formatLogOutput(template, outputs_section);
    }

    private void formatDefinedOutputs(Template template, JSONArray outputs_section) {
        for (DataObject outputObject : template.getOutputs()) {
            JSONObject out = new JSONObject();

            out.put("name", outputObject.getName());
            out.put("property", outputObject.getName());
            out.put("type", outputObject.getInfoTypeName());
            out.put("multiplicity", outputObject.getMultiplicityName());
            out.put("retain", debug || outputObject.getRetain());
            outputs_section.add(out);
        }
    }

    private void formatLogOutput(Template template, JSONArray outputs_section) {
        JSONObject out = new JSONObject();
        out.put("name", "logs");
        out.put("property", "logs");
        out.put("type", "File");
        out.put("multiplicity", "collection");
        out.put("retain", true);
        outputs_section.add(out);
    }

    private void formatProperties(TransformationActivity analysis, Template template, TransformationStep currentStep,
            Transformation transformation, JSONArray params, Set<String> keyset, JSONObject config, JSONArray inputs,
            JSONArray stepArray)
            throws NumberFormatException {

        long workspaceId = Long.parseLong(experiment.getString("workspace_id"));
        String stepName = currentStep.getName();
        for (PropertyGroup group : template.getPropertyGroups()) {
            List<Property> properties = group.getProperties();

            for (Property p : properties) {
                if (p.getPropertyType().getName().equals("Info")) {
                    continue;
                }

                String key = stepName + "_" + p.getId();
                if (transformation.containsProperty(p.getId())) {
                    params.add(formatPropertyFromTransformation(p, transformation));

                }
                else if (keyset.contains(key) || !p.getIsVisible()) {
                    String value = keyset.contains(key) ? config.getString(key) : getDefaultValue(p);
                    List<JSONObject> objects = buildParamsForProperty(p, value, inputs, workspaceId, stepName);
                    params.addAll(objects);
                }
                else if (p.getDataObject() != null && analysis.isTargetInMapping(currentStep.getName(), p.getId())) {
                    formatMappedInput(analysis, currentStep, p.getDataObject(), stepArray, params);
                }
            }
        }
    }

    private void formatUnreferencedInputProperties(Template template, TransformationStep currentStep,
            Set<String> keyset, JSONObject config, JSONArray params, Transformation transformation,
            TransformationActivity analysis, JSONArray stepArray) {
        for (DataObject currentInput : template.findUnreferencedInputs()) {
            // this is temporary - we're skipping the resolution of
            // any input DataObject of type "ReconcileTaxa" because
            // the resolution is not implemented yet... (lenards)
            if (currentInput.getInfoTypeName().equalsIgnoreCase("reconciletaxa")) {
                continue;
            }

            String key = currentStep.getName() + "_" + currentInput.getId();
            if (keyset.contains(key)) {
                String path = config.getString(key);
                if (!StringUtils.isBlank(path)) {
                    JSONArray objects = getInputJSONObjects(currentInput, path);
                    addParameterDefinitionsForDataObject(currentStep.getName(), params, currentInput, objects);
                }
            }
            else if (transformation.containsProperty(currentInput.getId())) {
                JSONObject prop = new JSONObject();
                prop.put("name", currentInput.getSwitchString());
                prop.put("value", transformation.getValueForProperty(currentInput.getName()));
                prop.put("order", getDataObjectOrder(currentInput));
                prop.put("id", currentInput.getId());
                params.add(prop);
            }
            else if (analysis.isTargetInMapping(currentStep.getName(), currentInput.getId())) {
                formatMappedInput(analysis, currentStep, currentInput, stepArray, params);
            }
        }
    }

    private void formatMappedInput(TransformationActivity analysis, TransformationStep currentStep,
            DataObject currentInput, JSONArray stepArray, JSONArray params) {
        ArrayList<InputOutputMap> maps = analysis.getMappingsForTargetStep(currentStep.getName());
        LOG.debug("is target: " + currentInput.getId());
        for (InputOutputMap map : maps) {
            TransformationStep source = map.getSource();
            JSONObject jsonSource = retrieveJSONStep(stepArray, source.getName());
            Map<String, String> relation = map.getInput_output_relation();
            for (String sourceObject : relation.keySet()) {
                LOG.debug("Source object: " + sourceObject);
                JSONObject prop = new JSONObject();
                prop.put("name", currentInput.getSwitchString());
                prop.put("order", getDataObjectOrder(currentInput));
                prop.put("id", currentInput.getId());
                if (relation.get(sourceObject).equals(currentInput.getId())) {
                    prop.put("value", retrieveValueForProperty(sourceObject, source, jsonSource));
                    params.add(prop);
                }
            }
        }
    }

    private void formatInputs(Template template, TransformationStep currentStep,
            Set<String> keyset, JSONObject config, JSONArray jinputs) {
        for (DataObject currentInput : template.getInputs()) {
            // this is temporary - we're skipping the resolution of
            // any input DataObject of type "ReconcileTaxa" because
            // the resolution is not implemented yet... (lenards)
            if (currentInput.getInfoTypeName().equalsIgnoreCase("reconciletaxa")) {
                continue;
            }
            String key = currentStep.getName() + "_" + currentInput.getId();
            if (keyset.contains(key)) {
                String path = config.getString(key);
                if (!StringUtils.isBlank(path)) {
                    jinputs.addAll(getInputJSONObjects(currentInput, path));
                }
            }
        }
    }

    /**
     * Formats a property using the value contained in the transformation. Note that the property's omit-if-blank
     * setting is ignored for properties whose values are obtained from the transformation. This was done to maintain
     * backward compatibility.
     *
     * @param property the property.
     * @param transformation the transformation.
     * @return the formatted parameter.
     */
    private JSONObject formatPropertyFromTransformation(Property property, Transformation transformation) {
        JSONObject jprop = new JSONObject();
        jprop.put("name", property.getName());
        jprop.put("value", transformation.getValueForProperty(property.getId()));
        jprop.put("order", getPropertyOrder(property));
        jprop.put("id", property.getId());
        return jprop;
    }

    private JSONArray getInputJSONObjects(DataObject input, String path) {
        JSONArray result = new JSONArray();

        logDataObject("input", input);

        if (fileInfo.canHandleFileType(input.getInfoTypeName())) {
            JSONObject inputJson = createInputJsonForResolvedFile(extractInputName(path), input);
            if (inputJson != null) {
                result.add(inputJson);
            }
        }
        else {
            if (!input.getMultiplicityName().equals("many")) {
                result.add(createInputJson(path, input));
            }
            else {
                LOG.warn("File IDs: " + path);

                JSONArray jsonFiles = (JSONArray) JSONSerializer.toJSON(path);
                for (int i = 0, pathCount = jsonFiles.size(); i < pathCount; i++) {
                    String currentPath = jsonFiles.getString(i);
                    result.add(createInputJson(currentPath, input));
                }
            }
        }

        return result;
    }

    private String extractInputName(String path) {
        JSONObject json = jsonObjectFromString(path);
        return json == null ? path : json.optString("name");
    }

    private JSONObject jsonObjectFromString(String path) {
        JSONObject json = null;
        try {
            json = (JSONObject) JSONSerializer.toJSON(path);
        }
        catch (Exception ignore) {
        }
        return json;
    }

    private void logDataObject(String label, DataObject input) {
        if (LOG.isDebugEnabled()) {
            try {
                LOG.debug(label + ": " + input.toJson().toString(JSON_INDENT));
            }
            catch (Exception ignore) {
            }
        }
    }

    private JSONObject createInputJsonForResolvedFile(String name, DataObject input) {
        JSONObject result = null;
        String url = resolveFile(name, input);
        if (!StringUtils.isBlank(url) && !isFileUrl(url)) {
            result = new JSONObject();
            result.put("name", name);
            result.put("property", name);
            result.put("type", input.getInfoTypeName().trim());
            result.put("value", url);
            result.put("id", input.getId());
            result.put("retain", debug || input.getRetain());
        }
        return result;
    }

    private String resolveFile(String name, DataObject input) {
        String retval = null;
        if (!StringUtils.isBlank(name)) {
            JSONObject query = new JSONObject();
            query.put("name", name);
            query.put("type", input.getInfoTypeName().trim());
            JSONObject result = (JSONObject) JSONSerializer.toJSON(fileInfo.getSingleFileAccessUrl(query.toString()));
            retval = result.getJSONObject("urlInfo").getString("url");
        }
        return retval;
    }

    private boolean isFileUrl(String url) {
        return FILE_URL_PATTERN.matcher(url).find();
    }

    private JSONObject createInputJson(String path, DataObject input) {
        JSONObject in = new JSONObject();
        String filename = basename(path);
        in.put("name", filename);
        in.put("property", filename);
        in.put("type", input.getInfoTypeName().trim());
        in.put("value", urlAssembler.assembleUrl(path));
        in.put("id", input.getId());
        in.put("multiplicity", input.getMultiplicityName());
        in.put("retain", debug || input.getRetain());
        return in;
    }

    private String basename(String path) {
        int slashpos = path.lastIndexOf("/");
        return slashpos == -1 ? path : path.substring(slashpos + 1);
    }

    private void addParameterDefinitionsForDataObject(String stepName, JSONArray params, DataObject dataObject,
            JSONArray dataInfo) {
        List<String> paths = getPathsForDataObject(stepName, dataObject, dataInfo);
        for (String path : paths) {
            params.add(getParameterDefinitionForDataObject(dataObject, path));
        }
    }

    private List<String> getPathsForDataObject(String stepName, DataObject dataObject, JSONArray dataInfo) {
        List<String> paths = new ArrayList<String>();
        if (dataObject.getMultiplicityName().equals("single")) {
            paths.addAll(getPathsForSingleInput(stepName, dataObject, dataInfo));
        }
        else {
            paths.addAll(getPathsForMultipleInputs(dataInfo));
        }
        return paths;
    }

    private List<String> getPathsForMultipleInputs(JSONArray dataInfo) {
        List<String> paths = new ArrayList<String>();
        for (int i = 0; i < dataInfo.size(); i++) {
            paths.add(dataInfo.getJSONObject(i).getString("property"));
        }
        return paths;
    }

    /**
     * Gets the list of paths to use for a single input file, which may be empty. This is brittle, but we need to treat
     * reference genomes as a special case because an input object is not created for reference genomes. I'm afraid a
     * complete refactoring of this class would be required to find a better solution, though.
     *
     * @param stepName the name of the current transformation step.
     * @param input the data object representing the input object.
     * @param dataInfo the list of input objects.
     * @return the list of paths to use for the input file, which may contain zero elements or one element.
     */
    private List<String> getPathsForSingleInput(String stepName, DataObject input, JSONArray dataInfo) {
        List<String> result = new ArrayList<String>();
        if (isReferenceGenome(input)) {
            String key = stepName + "_" + input.getId();
            String propertyValue = experiment.getJSONObject("config").getString(key);
            String referenceGenomeName = extractInputName(propertyValue);
            String resolvedPath = resolveFile(referenceGenomeName, input);
            if (!StringUtils.isBlank(resolvedPath)) {
                result.add(resolvedPath);
            }
        }
        else if (dataInfo.size() > 0) {
            result.add(dataInfo.getJSONObject(0).getString("property"));
        }
        return result;
    }

    private boolean isReferenceGenome(DataObject dataObject) {
        return REFERENCE_GENOME_INFO_TYPES.contains(dataObject.getInfoTypeName());
    }

    /**
     * Returns an array of objects representing the given input objects
     *
     * @param dataobject the data object for which the param needs to be retrieved
     * @param path the relative path to the file.
     */
    private JSONObject getParameterDefinitionForDataObject(DataObject dataObject, String path) {
        JSONObject parameter = new JSONObject();
        parameter.put("name", dataObject.getSwitchString());
        parameter.put("order", getDataObjectOrder(dataObject));
        parameter.put("value", path);
        parameter.put("id", dataObject.getId());
        return parameter;
    }

    protected int getDataObjectOrder(DataObject dataObject) {
        int order = dataObject.getOrderd();
        if (order < 0 && !StringUtils.isBlank(dataObject.getSwitchString())) {
            order = 0;
        }
        return order;
    }

    public String retrieveValueForProperty(String property, TransformationStep step, JSONObject jstep) {

        Transformation transformation = step.getTransformation();

        String originalName = property.replace("in#", "").replace(step.getName() + "_", "");

        if (property.contains("in#")) {

            JSONObject jsonInput = getJSONProperty(jstep, originalName);
            if (jsonInput != null) {
                return jsonInput.getString("value");
            }
            else {
                throw new WorkflowException("A value for property " + step.getName() + "_" + originalName
                        + " needs to be input in order to be used in a mapping.");
            }
        }

        if (transformation.containsProperty(originalName)) {
            return transformation.getPropertyValues().get(originalName);
        }
        else {
            String templateId = transformation.getTemplate_id();
            Template template = daoFactory.getTemplateDao().findById(templateId);
            if (template == null) {
                throw new WorkflowException("template " + templateId + " not found");
            }
            return getPropertyName(originalName, template);
        }
    }

    protected String getPropertyName(String originalName, Template template) {
        String retval = null;
        try {
            retval = template.getOutputName(originalName);
        }
        catch (Exception e) {
            throw new WorkflowException("unable to determine the output name for " + originalName, e);
        }
        return retval;
    }

    public JSONObject getJSONProperty(JSONObject step, String name) {
        JSONObject config = step.getJSONObject("config");
        JSONArray inputs = config.getJSONArray("input");

        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.getJSONObject(i).getString("id").equals(name)) {
                return inputs.getJSONObject(i);
            }
        }

        JSONArray params = config.getJSONArray("params");

        for (int i = 0; i < params.size(); i++) {
            if (params.getJSONObject(i).getString("id").equals(name)) {
                return params.getJSONObject(i);
            }
        }

        return null;
    }

    public JSONObject retrieveJSONStep(JSONArray steps, String step_name) {

        for (int i = 0; i < steps.size(); i++) {
            if (steps.getJSONObject(i).getString("name").equals(step_name)) {
                return steps.getJSONObject(i);
            }
        }
        return null;
    }

    private List<JSONObject> buildParamsForProperty(Property property, String value, JSONArray inputs,
            long workspaceId, String stepName) {
        List<JSONObject> jprops = new ArrayList<JSONObject>();
        String propertyTypeName = property.getPropertyTypeName();
        if (StringUtils.equals(propertyTypeName, "Selection") ||
            StringUtils.equals(propertyTypeName, "ValueSelection")) {
            CollectionUtils.addIgnoreNull(jprops, formatSelectionProperty(property, value));
        }
        else if (StringUtils.equals(propertyTypeName, "Flag")) {
            CollectionUtils.addIgnoreNull(jprops, formatFlagProperty(property, value));
        }
        else if (StringUtils.equals(propertyTypeName, "QuotedText")) {
            CollectionUtils.addIgnoreNull(jprops, formatQuotedTextProperty(property, value));
        }
        else if (StringUtils.equals(propertyTypeName, "BarcodeSelector") ||
                 StringUtils.equals(propertyTypeName, "ClipperSelector")) {
            CollectionUtils.addIgnoreNull(jprops, formatBarcodeSelectorProperty(property, value, inputs, workspaceId));
        }
        else if (StringUtils.equals(propertyTypeName, "Input")) {
            jprops.addAll(formatInputProperties(property, value, stepName));
        }
        else if (StringUtils.equals(propertyTypeName, "Output")) {
            CollectionUtils.addIgnoreNull(jprops, formatOutputProperty(property, value));
        }
        else {
            CollectionUtils.addIgnoreNull(jprops, formatDefaultProperty(property, value));
        }

        return jprops;
    }

    protected JSONObject formatOutputProperty(Property property, String value) {
        if (property.getDataObject().isImplicit()) {
            return null;
        }

        return formatDefaultProperty(property, value);
    }

    protected JSONObject formatDefaultProperty(Property property, String value) {
        JSONObject jprop = null;
        if (!property.getOmitIfBlank() || !StringUtils.isBlank(value)) {
            jprop = initialPropertyJson(property);
            jprop.put("name", property.getName());
            jprop.put("value", value);
            jprop.put("id", property.getId());
        }
        return jprop;
    }

    /**
     * Formats a bar code selector property. Note that this method ignores the property's omitIfBlank setting. This is a
     * highly specialized type of property that is currently deprecated, so special handling of optional properties is
     * not required for this type of property at this time.
     *
     * @param property the property.
     * @param value the property value.
     * @param inputs the array of inputs.
     * @param workspaceId the user's workspace identifier.
     * @return the formatted parameter.
     */
    protected JSONObject formatBarcodeSelectorProperty(Property property, String value, JSONArray inputs,
            long workspaceId) {
        JSONObject jprop = initialPropertyJson(property);
        String filename;
        String url;
        if (value.contains("/")) {
            filename = basename(value);
            url = urlAssembler.assembleUrl(value);
        }
        else {
            JSONObject resolvedFileInfo = resolveBarcodeFile(value, workspaceId);
            filename = resolvedFileInfo.getString("file_name");
            url = resolvedFileInfo.getString("url");
        }

        jprop.put("name", property.getName());
        jprop.put("value", filename);
        jprop.put("type", "File");
        jprop.put("id", property.getId());

        JSONObject jinput = new JSONObject();

        jinput.put("id", property.getId());
        jinput.put("name", "1");
        jinput.put("value", url + " ");
        jinput.put("order", getPropertyOrder(property));
        jinput.put("multiplicity", "single");

        inputs.add(jinput);
        return jprop;
    }

    protected JSONObject formatQuotedTextProperty(Property property, String value) {
        JSONObject jprop = null;
        if (!property.getOmitIfBlank() || !StringUtils.isBlank(value)) {
            jprop = initialPropertyJson(property);
            jprop.put("name", property.getName());
            jprop.put("value", "\"" + value + "\"");
            jprop.put("id", property.getId());
        }
        return jprop;
    }

    /**
     * Formats a flag property. Note that this method ignores the property's omit-if-blank setting, which isn't required
     * for flag properties, which are always omitted if the option selected by the user corresponds to a missing
     * command-line flag.
     *
     * @param property the property being formatted.
     * @param value the property value.
     * @return the formatted property or null if the property should not be included on the command line.
     */
    protected JSONObject formatFlagProperty(Property property, String value) {
        JSONObject jprop = null;

        // Parse the boolean value and the list of possible values.
        boolean booleanValue = Boolean.parseBoolean(value.trim());
        String[] values = property.getName().split(",");

        // Determine which value was selected.
        int index = booleanValue ? 0 : 1;
        String selectedValue = values.length > index ? values[index] : null;

        // Format the property only if a value was selected.
        if (selectedValue != null && !StringUtils.isBlank(selectedValue)) {
            String[] components = selectedValue.split("\\s+|=", 2);
            jprop = new JSONObject();
            jprop.put("id", property.getId());
            jprop.put("name", components[0]);
            jprop.put("value", components.length > 1 ? components[1] : "");
            jprop.put("order", property.getOrder());
        }

        return jprop;
    }

    private JSONObject formatSelectionProperty(Property property, String arg) {
        JSONObject result;
        if (isJsonObject(arg)) {
            result = formatNewStyleSelectionProperty(property, arg);
        }
        else {
            result = formatOldStyleSelectionProperty(property, arg);
        }
        return result;
    }

    /**
     * Formats an old-style selection property. Note that this method ignores the property's omit-if-blank setting,
     * which isn't required for old-style properties because the property and value are both encoded in the value that
     * is specified for each selection.
     *
     * @param property the property.
     * @param arg the argument.
     * @return the formatted parameter or null if the parameter shouldn't be formatted.
     */
    private JSONObject formatOldStyleSelectionProperty(Property property, String arg) {
        JSONObject result = null;
        String[] possibleValues = property.getName().split(",");
        int index = Integer.parseInt(arg);
        if (index >= 0 && possibleValues.length > index) {
            result = initialPropertyJson(property);
            String selectedValue = possibleValues[index];
            String[] components = selectedValue.split("\\s+|=", 2);
            result.put("id", property.getId());
            result.put("name", components[0]);
            if (components.length > 1) {
                result.put("value", components[1]);
            }
        }
        return result;
    }

    /**
     * Formats a new-style selection property. Note that this method ignores the property's omit-if-blank setting, which
     * isn't required for new-style selection properties because the name and value are specified separately for each
     * selection.
     *
     * @param property the property.
     * @param arg the argument.
     * @return the formatted parameter or null if the parameter shouldn't be formatted.
     */
    private JSONObject formatNewStyleSelectionProperty(Property property, String arg) {
        JSONObject result = null;
        JSONObject json = (JSONObject) JSONSerializer.toJSON(arg);
        String name = json.optString("name");
        String value = json.optString("value");
        if (!StringUtils.isEmpty(name) || !StringUtils.isEmpty(value)) {
            result = initialPropertyJson(property);
            result.put("id", property.getId());
            putGivenValueOrSpace(result, "name", name);
            putGivenValueOrSpace(result, "value", value);
        }
        return result;
    }

    private void putGivenValueOrSpace(JSONObject json, String key, String value) {
        json.put(key, StringUtils.isBlank(value) ? " " : value);
    }

    /**
     * Determines whether or not the given string represents a JSON object.
     *
     * @param value the string to check.
     * @return true if the string represents a JSON object.
     */
    private boolean isJsonObject(String value) {
        try {
            JSON json = JSONSerializer.toJSON(value);
            return !json.isArray();
        }
        catch (Exception e) {
            return false;
        }
    }

    private JSONObject initialPropertyJson(Property property) {
        JSONObject json = new JSONObject();
        json.put("order", getPropertyOrder(property));
        return json;
    }

    protected int getPropertyOrder(Property property) {
        int order = property.getOrder();
        if (order < 0 && !StringUtils.isBlank(property.getName())) {
            order = 0;
        }
        return order;
    }

    protected JSONObject resolveBarcodeFile(String value, long workspaceId) {
        JSONObject tempObject = new JSONObject();
        JSONArray array = new JSONArray();
        JSONObject info = new JSONObject();
        info.put("name", Long.toString(workspaceId) + "," + value);
        info.put("type", "BarcodeSelector");
        array.add(info);
        tempObject.put("input", array);
        JSONArray output = (JSONArray) JSONSerializer.toJSON(fileInfo.getFilesAccessUrls(tempObject.toString()));
        JSONObject root = output.getJSONObject(0);
        JSONObject resolvedFileInfo = root.getJSONObject("urlInfo");
        return resolvedFileInfo;
    }

    private List<JSONObject> formatInputProperties(Property property, String value, String stepName) {
        List<JSONObject> params = new ArrayList<JSONObject>();
        JSONArray objects = getInputJSONObjects(property.getDataObject(), value);
        List<String> paths = getPathsForDataObject(stepName, property.getDataObject(), objects);
        for (String path : paths) {
            if (!property.getOmitIfBlank() || !StringUtils.isBlank(path)) {
                params.add(getParameterDefinitionForDataObject(property.getDataObject(), path));
            }
        }
        return params;
    }

    private String getDefaultValue(Property property) {
        String type = property.getPropertyTypeName();
        return type.equalsIgnoreCase("output") ? property.getDataObject().getName() : property.getDefaultValue();
    }
}
