package com.yogitha.dobby

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WorkflowStep(
    val action: String,
    val app: String? = null,
    val query: String? = null,
    val setting: String? = null,
    val state: String? = null,
    val value: Int? = null,
    val enabled: Boolean? = null,
    val contact: String? = null,
    val uiElement: String? = null, // Semantic description of UI element
    val description: String? = null // Human-readable description
)

data class Workflow(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<WorkflowStep>,
    val variables: Map<String, String> = emptyMap(), // Variable names to their semantic types
    val createdAt: Long = System.currentTimeMillis()
)

object WorkflowManager {
    private var workflows: MutableList<Workflow> = mutableListOf()
    private var currentTeachingSession: TeachingSession? = null

    data class TeachingSession(
        val workflowName: String,
        val steps: MutableList<WorkflowStep> = mutableListOf(),
        val variables: MutableMap<String, String> = mutableMapOf(),
        val startTime: Long = System.currentTimeMillis()
    )

    fun startTeaching(workflowName: String): TeachingSession {
        val session = TeachingSession(workflowName)
        currentTeachingSession = session
        return session
    }

    fun stopTeaching(): Workflow? {
        val session = currentTeachingSession ?: return null
        
        // Get accessibility steps if available
        val accessibilitySteps = DobbyAccessibilityService.getTeachingSteps()
        
        // Merge observed steps with captured actions
        val combinedSteps = if (accessibilitySteps.isNotEmpty()) {
            convertAccessibilityStepsToWorkflowSteps(accessibilitySteps, session.steps)
        } else {
            session.steps.toList()
        }
        
        if (combinedSteps.isEmpty()) {
            currentTeachingSession = null
            DobbyAccessibilityService.clearTeachingSteps()
            return null
        }

        // Extract variables from steps
        val variables = extractVariables(combinedSteps)

        val workflow = Workflow(
            id = "workflow_${System.currentTimeMillis()}",
            name = session.workflowName,
            description = generateWorkflowDescription(combinedSteps),
            steps = combinedSteps,
            variables = variables,
            createdAt = session.startTime
        )

        workflows.add(workflow)
        currentTeachingSession = null
        DobbyAccessibilityService.clearTeachingSteps()
        return workflow
    }
    
    private fun convertAccessibilityStepsToWorkflowSteps(
        accessibilitySteps: List<TeachingStep>,
        actionSteps: List<WorkflowStep>
    ): List<WorkflowStep> {
        val workflowSteps = mutableListOf<WorkflowStep>()
        
        for (accessibilityStep in accessibilitySteps) {
            val action = when {
                accessibilityStep.eventType == "TYPE_VIEW_CLICKED" -> "tap"
                accessibilityStep.eventType == "TYPE_VIEW_TEXT_CHANGED" -> "type"
                accessibilityStep.eventType == "TYPE_WINDOW_STATE_CHANGED" -> "navigate"
                else -> "unknown"
            }
            
            // Create semantic description
            val description = when {
                accessibilityStep.text.isNotBlank() -> "Tap on: ${accessibilityStep.text}"
                accessibilityStep.contentDescription.isNotBlank() -> "Tap on: ${accessibilityStep.contentDescription}"
                accessibilityStep.className?.contains("EditText") == true -> "Text field"
                accessibilityStep.className?.contains("Button") == true -> "Button"
                else -> "UI element"
            }
            
            workflowSteps.add(WorkflowStep(
                action = action,
                app = accessibilityStep.packageName,
                uiElement = accessibilityStep.text.takeIf { it.isNotBlank() } ?: accessibilityStep.contentDescription,
                description = description
            ))
        }
        
        // Add any explicit action steps from voice commands
        workflowSteps.addAll(actionSteps)
        
        return workflowSteps
    }
    
    private fun extractVariables(steps: List<WorkflowStep>): Map<String, String> {
        val variables = mutableMapOf<String, String>()
        
        for (step in steps) {
            // Extract query-like values
            if (step.action == "amazon_search" || step.action == "play_song") {
                step.query?.let { 
                    variables["query"] = "product_query"
                }
            }
            if (step.action == "call_contact") {
                step.contact?.let {
                    variables["contact"] = "contact_name"
                }
            }
        }
        
        return variables
    }
    
    private fun generateWorkflowDescription(steps: List<WorkflowStep>): String {
        val descriptions = steps.mapNotNull { it.description }.take(3)
        return if (descriptions.isNotEmpty()) {
            "Workflow: ${descriptions.joinToString(" → ")}"
        } else {
            "Custom workflow"
        }
    }

    fun isTeaching(): Boolean = currentTeachingSession != null

    fun getCurrentSession(): TeachingSession? = currentTeachingSession

    fun addStep(step: WorkflowStep) {
        currentTeachingSession?.steps?.add(step)
    }

    fun addVariable(name: String, type: String) {
        currentTeachingSession?.variables?.put(name, type)
    }

    fun getWorkflows(): List<Workflow> = workflows.toList()

    fun findMatchingWorkflow(userIntent: String): Workflow? {
        val intentLower = userIntent.lowercase()
        
        for (workflow in workflows) {
            val nameLower = workflow.name.lowercase()
            val descLower = workflow.description.lowercase()
            
            // Check for workflow name match
            if (intentLower.contains(nameLower) || nameLower.contains(intentLower)) {
                return workflow
            }
            
            // Check for semantic pattern matching
            if (matchesWorkflowPattern(intentLower, workflow)) {
                return workflow
            }
            
            // Check if any step description matches
            for (step in workflow.steps) {
                step.description?.let { desc ->
                    if (intentLower.contains(desc.lowercase()) || desc.lowercase().contains(intentLower)) {
                        return workflow
                    }
                }
            }
        }
        
        return null
    }
    
    private fun matchesWorkflowPattern(intent: String, workflow: Workflow): Boolean {
        // Check for Amazon shopping patterns
        if (intent.contains("amazon") && workflow.description.contains("amazon", ignoreCase = true)) {
            return true
        }
        
        // Check for Spotify patterns
        if (intent.contains("spotify") || intent.contains("play") && 
            workflow.description.contains("spotify", ignoreCase = true)) {
            return true
        }
        
        // Check for action patterns
        val intentActions = detectActionsInIntent(intent)
        val workflowActions = workflow.steps.map { it.action }.toSet()
        
        return intentActions.intersect(workflowActions).isNotEmpty()
    }
    
    private fun detectActionsInIntent(intent: String): Set<String> {
        val actions = mutableSetOf<String>()
        
        if (intent.contains("search") || intent.contains("find")) actions.add("search")
        if (intent.contains("play")) actions.add("play")
        if (intent.contains("call")) actions.add("call")
        if (intent.contains("open") || intent.contains("launch")) actions.add("open")
        if (intent.contains("cart")) actions.add("add_to_cart")
        
        return actions
    }

    fun generalizeWorkflow(workflow: Workflow, newParameters: Map<String, String>): List<WorkflowStep> {
        return workflow.steps.map { step ->
            var generalizedStep = step
            
            // Replace variable values with new parameters
            for ((variable, value) in newParameters) {
                when (variable) {
                    "query" -> generalizedStep = step.copy(query = value)
                    "contact" -> generalizedStep = step.copy(contact = value)
                    "app" -> generalizedStep = step.copy(app = value)
                    "setting" -> generalizedStep = step.copy(setting = value)
                }
            }
            
            generalizedStep
        }
    }

    suspend fun saveWorkflows(context: Context) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, "learned_workflows.json")
            val jsonArray = JSONArray()
            
            for (workflow in workflows) {
                val workflowJson = JSONObject().apply {
                    put("id", workflow.id)
                    put("name", workflow.name)
                    put("description", workflow.description)
                    put("created_at", workflow.createdAt)
                    
                    val stepsArray = JSONArray()
                    for (step in workflow.steps) {
                        val stepJson = JSONObject().apply {
                            put("action", step.action)
                            step.app?.let { put("app", it) }
                            step.query?.let { put("query", it) }
                            step.setting?.let { put("setting", it) }
                            step.state?.let { put("state", it) }
                            step.value?.let { put("value", it) }
                            step.enabled?.let { put("enabled", it) }
                            step.contact?.let { put("contact", it) }
                            step.uiElement?.let { put("ui_element", it) }
                            step.description?.let { put("description", it) }
                        }
                        stepsArray.put(stepJson)
                    }
                    put("steps", stepsArray)
                    
                    val variablesJson = JSONObject()
                    for ((key, value) in workflow.variables) {
                        variablesJson.put(key, value)
                    }
                    put("variables", variablesJson)
                }
                jsonArray.put(workflowJson)
            }
            
            file.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            android.util.Log.e("WorkflowManager", "Failed to save workflows", e)
        }
    }

    suspend fun loadWorkflows(context: Context) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, "learned_workflows.json")
            if (!file.exists()) return@withContext
            
            val content = file.readText()
            val jsonArray = JSONArray(content)
            workflows.clear()
            
            for (i in 0 until jsonArray.length()) {
                val workflowJson = jsonArray.getJSONObject(i)
                val steps = mutableListOf<WorkflowStep>()
                val variables = mutableMapOf<String, String>()
                
                val stepsArray = workflowJson.getJSONArray("steps")
                for (j in 0 until stepsArray.length()) {
                    val stepJson = stepsArray.getJSONObject(j)
                    steps.add(WorkflowStep(
                        action = stepJson.getString("action"),
                        app = stepJson.optString("app").takeIf { it.isNotEmpty() },
                        query = stepJson.optString("query").takeIf { it.isNotEmpty() },
                        setting = stepJson.optString("setting").takeIf { it.isNotEmpty() },
                        state = stepJson.optString("state").takeIf { it.isNotEmpty() },
                        value = stepJson.optInt("value").takeIf { it != 0 },
                        enabled = stepJson.optBoolean("enabled").takeIf { it },
                        contact = stepJson.optString("contact").takeIf { it.isNotEmpty() },
                        uiElement = stepJson.optString("ui_element").takeIf { it.isNotEmpty() },
                        description = stepJson.optString("description").takeIf { it.isNotEmpty() }
                    ))
                }
                
                val variablesJson = workflowJson.optJSONObject("variables")
                if (variablesJson != null) {
                    val keys = variablesJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        variables[key] = variablesJson.getString(key)
                    }
                }
                
                workflows.add(Workflow(
                    id = workflowJson.getString("id"),
                    name = workflowJson.getString("name"),
                    description = workflowJson.getString("description"),
                    steps = steps,
                    variables = variables,
                    createdAt = workflowJson.getLong("created_at")
                ))
            }
        } catch (e: Exception) {
            android.util.Log.e("WorkflowManager", "Failed to load workflows", e)
        }
    }

    fun clearWorkflows() {
        workflows.clear()
        currentTeachingSession = null
    }
}