package org.iplantc.workflow.integration.validation;

import org.iplantc.workflow.WorkflowException;
import org.iplantc.workflow.model.Template;

/**
 * A template validator that always fails.
 * 
 * @author Dennis Roberts
 */
public class FailingTemplateValidator implements TemplateValidator {

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate(Template template) {
        throw new WorkflowException("validation failed");
    }
}
