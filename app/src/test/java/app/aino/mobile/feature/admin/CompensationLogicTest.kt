package app.aino.mobile.feature.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompensationLogicTest {
    private val allKeys = DEFAULT_COMPONENTS.associate { it.key to "0" }

    @Test
    fun calcFromCtcMatchesTheWebFormula() {
        // 6,00,000 / 12 = 50,000; basic 40% = 20,000; HRA 50% of basic = 10,000;
        // conveyance 5% = 2,500; special = rest 17,500; PF 12% of basic capped at 1,800; PT 200.
        val split = calcFromCtc(600000.0, CtcConfig(), allKeys)
        assertEquals(50000.0, split.baseSalary, 0.0)
        assertEquals(
            mapOf(
                "basic" to "20000", "hra" to "10000", "conveyance" to "2500", "special_allowance" to "17500",
                "_ded_pf" to "1800", "_ded_professional_tax" to "200", "_ded_tds" to "0",
            ),
            split.components,
        )
        // Uncapped PF, and JS Math.round halves (7 / 12 = 0.583 -> 1).
        assertEquals("1080", calcFromCtc(270000.0, CtcConfig(), allKeys).components["_ded_pf"])
        assertEquals(1.0, calcFromCtc(7.0, CtcConfig(), emptyMap()).baseSalary, 0.0)
    }

    @Test
    fun calcFromCtcOnlyTouchesKeysTheFormHas() {
        val split = calcFromCtc(120000.0, null, mapOf("basic" to "1", "bonus" to "500"))
        assertEquals(mapOf("basic" to "4000", "bonus" to "500"), split.components)
        assertTrue(calcFromCtc(120000.0, null, emptyMap()).components.isEmpty())
    }

    @Test
    fun assignFormCtcAndTemplateBehaviour() {
        val template = CompensationTemplate(3, "Std", components = DEFAULT_COMPONENTS)
        val form = newAssignForm("2026-09-26").copy(userId = 4)
        // A template seeds its keys at 0; with no CTC nothing is recalculated.
        val seeded = form.withTemplate(template, null)
        assertEquals(3L, seeded.templateId)
        assertEquals("0", seeded.components["hra"])
        // Entering a CTC recalculates base + known components.
        val filled = seeded.withCtc("600000", null)
        assertEquals("50000", filled.baseSalary)
        assertEquals("10000", filled.components["hra"])
        // Zero / junk CTC keeps the numbers, only the text changes.
        assertEquals("50000", filled.withCtc("abc", null).baseSalary)
        // A template after a CTC recalculates its fresh keys; "No template" keeps amounts.
        assertEquals("20000", form.copy(ctcAnnual = "600000").withTemplate(template, null).components["basic"])
        assertEquals(filled.components, filled.withTemplate(null, null).components)
        assertNull(filled.withTemplate(null, null).templateId)
        assertTrue(filled.canSave)
        assertFalse(newAssignForm("2026-09-26").canSave)
    }

    @Test
    fun editFormPrefillsFromTheActiveRecord() {
        val emp = EmployeeCompensation(
            id = 1, userId = 9, ctcAnnual = 0.0, baseSalary = 42000.5, templateId = 2, fullName = "Ann",
            components = mapOf("hra" to kotlinx.serialization.json.JsonPrimitive("8000.00")),
        )
        val form = editAssignForm(emp, "2026-09-26")
        assertEquals("Edit Compensation \u2014 Ann", form.title)
        assertEquals("", form.ctcAnnual)
        assertEquals("42000.5", form.baseSalary)
        assertEquals(mapOf("hra" to "8000"), form.components)
        assertEquals("2026-09-26", form.effectiveFrom)
        assertEquals("Assign Compensation", newAssignForm("x").title)
    }

    @Test
    fun templateFormValidationAndLabels() {
        assertFalse(TemplateForm().canSave)
        assertTrue(TemplateForm(name = "Std").canSave)
        assertFalse(TemplateForm(name = "Std", components = DEFAULT_COMPONENTS + BLANK_COMPONENT).canSave)
        assertEquals("professional tax", componentLabel("_ded_professional_tax"))
        assertEquals("special allowance", componentLabel("special_allowance"))
        val t = CompensationTemplate(5, "Std", null, null, isDefault = true)
        assertEquals(TemplateForm(5, "Std", "", emptyList(), true), templateFormFor(t))
    }
}
