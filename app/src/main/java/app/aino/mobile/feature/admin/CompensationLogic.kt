package app.aino.mobile.feature.admin

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** `CompensationSetup.tsx` tab bar. */
enum class CompTab(val label: String) { Templates("Templates"), Employees("Employees"), Ctc("CTC Settings"), Bank("Bank Verifications") }

/** `DEFAULT_COMPONENTS`: the template form's starting rows. */
val DEFAULT_COMPONENTS = listOf(
    CompComponent("basic", "Basic Salary", "earning", "fixed", taxable = true),
    CompComponent("hra", "HRA", "earning", "fixed", taxable = true),
    CompComponent("conveyance", "Conveyance Allowance", "earning", "fixed", taxable = false),
    CompComponent("special_allowance", "Special Allowance", "earning", "fixed", taxable = true),
    CompComponent("_ded_pf", "Provident Fund", "deduction", "fixed", taxable = false),
    CompComponent("_ded_professional_tax", "Professional Tax", "deduction", "fixed", taxable = false),
    CompComponent("_ded_tds", "Income Tax (TDS)", "deduction", "fixed", taxable = false),
)

/** `addComponent` row. */
val BLANK_COMPONENT = CompComponent("", "", "earning", "fixed", taxable = false)

/** JavaScript `Math.round` (halves round toward +∞). */
internal fun jsRound(x: Double): Double = floor(x + 0.5)

/** An amount as an input value: `50000` rather than `50000.0`. */
fun plainAmount(value: Double?): String {
    val v = value ?: return ""
    return if (v == floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

/** `parseFloat(x) || 0`. */
internal fun parseAmount(text: String): Double = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

data class CtcSplit(val baseSalary: Double, val components: Map<String, String>)

/**
 * `calcFromCtc`: splits an annual CTC with the org's CTC config. Only keys the
 * form already has are overwritten (an empty component map stays empty).
 */
fun calcFromCtc(ctcAnnual: Double, config: CtcConfig?, current: Map<String, String>): CtcSplit {
    val cfg = config ?: CtcConfig()
    val monthly = jsRound(ctcAnnual / 12)
    val basic = jsRound(monthly * cfg.basicPct / 100)
    val hra = jsRound(basic * cfg.hraPct / 100)
    val conveyance = jsRound(monthly * cfg.conveyancePct / 100)
    val special = max(0.0, monthly - basic - hra - conveyance)
    val pf = min(cfg.pfMax, jsRound(basic * cfg.pfPct / 100))
    val ctcMap = mapOf(
        "basic" to basic, "hra" to hra, "conveyance" to conveyance, "special_allowance" to special,
        "_ded_pf" to pf, "_ded_professional_tax" to cfg.ptFixed, "_ded_tds" to 0.0,
    )
    val updated = current.mapValues { (key, value) -> ctcMap[key]?.let(::plainAmount) ?: value }
    return CtcSplit(monthly, updated)
}

/** Assign-compensation form (the web modal). [userId] is null for a new assignment until picked. */
data class AssignForm(
    val isNew: Boolean,
    val userId: Long? = null,
    val fullName: String? = null,
    val effectiveFrom: String = "",
    val ctcAnnual: String = "",
    val baseSalary: String = "",
    val components: Map<String, String> = emptyMap(),
    val templateId: Long? = null,
) {
    val title: String get() = if (isNew) "Assign Compensation" else "Edit Compensation \u2014 ${fullName ?: "Employee"}"
    val canSave: Boolean get() = userId != null && effectiveFrom.isNotBlank() && baseSalary.isNotBlank()
}

fun newAssignForm(today: String) = AssignForm(isNew = true, effectiveFrom = today)

/** The row's Edit button: effective today, current amounts prefilled. */
fun editAssignForm(emp: EmployeeCompensation, today: String) = AssignForm(
    isNew = false,
    userId = emp.userId,
    fullName = emp.fullName,
    effectiveFrom = today,
    ctcAnnual = if ((emp.ctcAnnual ?: 0.0) > 0) plainAmount(emp.ctcAnnual) else "",
    baseSalary = plainAmount(emp.baseSalary),
    components = emp.componentAmounts.mapValues { plainAmount(it.value) },
    templateId = emp.templateId,
)

/** `handleCtcChange`: a positive CTC recalculates base salary and the known components. */
fun AssignForm.withCtc(text: String, config: CtcConfig?): AssignForm {
    val ctc = parseAmount(text)
    if (ctc <= 0) return copy(ctcAnnual = text)
    val split = calcFromCtc(ctc, config, components)
    return copy(ctcAnnual = text, baseSalary = plainAmount(split.baseSalary), components = split.components)
}

/** Template select: its component keys at 0, recalculated when a CTC is entered; "No template" keeps amounts. */
fun AssignForm.withTemplate(template: CompensationTemplate?, config: CtcConfig?): AssignForm {
    if (template == null) return copy(templateId = null)
    val comps = template.components.orEmpty().associate { it.key to "0" }
    val base = copy(templateId = template.id, components = comps)
    val ctc = parseAmount(ctcAnnual)
    if (ctc <= 0) return base
    val split = calcFromCtc(ctc, config, comps)
    return base.copy(baseSalary = plainAmount(split.baseSalary), components = split.components)
}

/** Component row label: `key.replace(/_ded_/, "").replace(/_/g, " ")`. */
fun componentLabel(key: String): String = key.replaceFirst("_ded_", "").replace("_", " ")

/** Template form (`templateForm`); HTML `required` on name and each key / label. */
data class TemplateForm(
    val editingId: Long? = null,
    val name: String = "",
    val description: String = "",
    val components: List<CompComponent> = DEFAULT_COMPONENTS,
    val isDefault: Boolean = false,
) {
    val canSave: Boolean get() = name.isNotBlank() && components.all { it.key.isNotBlank() && it.label.isNotBlank() }
}

fun templateFormFor(t: CompensationTemplate) =
    TemplateForm(t.id, t.name, t.description.orEmpty(), t.components.orEmpty(), t.isDefault)
