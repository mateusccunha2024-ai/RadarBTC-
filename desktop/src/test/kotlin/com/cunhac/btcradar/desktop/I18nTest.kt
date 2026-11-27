package com.cunhac.btcradar.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Camada de idiomas: tabela exata, moldes com lacunas, máscara de números e maiúsculas. Sem rede. */
class I18nTest {
    @After fun back() = I18n.use("pt")

    @Test fun translatesExactTemplatesAndNumbers() {
        I18n.loadForTest("en", """
            Livro de ofertas	Order book
            Confiança {0}% (parte dos pesos com dado válido)	Confidence {0}% (share of weights with valid data)
            Faltam {0} para {1}	{1} in {0}
            Paredes	Walls
        """.trimIndent())
        I18n.use("en")
        assertEquals("Order book", L("Livro de ofertas"))
        assertEquals("Confidence 87% (share of weights with valid data)", L("Confiança 87% (parte dos pesos com dado válido)"))
        assertEquals("FOMC in 3 h", L("Faltam 3 h para FOMC"))          // lacunas podem trocar de ordem
        assertEquals("WALLS", L("PAREDES"))                                // títulos em maiúsculas
        assertEquals("12.345,6", L("12.345,6"))                          // só número: não traduz
        I18n.use("pt")
        assertEquals("Livro de ofertas", L("Livro de ofertas"))          // português = original
    }

    @Test fun masksNumbers() {
        val (m, nums) = I18n.mask("Delta 15 min -117,3 BTC · 2 de 3")
        assertEquals("Delta ⟦0⟧ min ⟦1⟧ BTC · ⟦2⟧ de ⟦3⟧", m)
        assertEquals(listOf("15", "-117,3", "2", "3"), nums)
        assertEquals("ZQZ26", I18n.mask("ZQZ26").first)                  // número colado em letras não é valor
    }
}
