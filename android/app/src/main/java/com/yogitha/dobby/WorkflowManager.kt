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
        if (session.steps.isEmpty()) {
            currentTeachingSession = null
            return null
        }

        val workflow = Workflow(
            id = "workflow_${System.currentTimeMillis()}",
            name = session.workflowName,
            description = "Learned from user demonstration",
            steps = session.steps.toList(),
            variables = session.variables.toMap(),
            createdAt = session.startTime
        )

        workflows.add(workflow)
        currentTeachingSession = null
        return workflow
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
        // Simple keyword matching for MVP
        val intentLower = userIntent.lowercase()
        
        for (workflow in workflows) {
            val nameLower = workflow.name.lowercase()
            val descLower = workflow.description.lowercase()
            
            // Check if workflow name or description matches intent
            if (intentLower.contains(nameLower) || nameLower.contains(intentLower)) {
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