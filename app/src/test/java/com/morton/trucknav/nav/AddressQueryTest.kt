package com.morton.trucknav.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressQueryTest {
    @Test fun stripsSuiteAndZip() {
        assertEquals("8131 Gateway Dr, Argyle, TX", AddressQuery.normalize("8131 Gateway Dr Ste 500, Argyle, TX 76226"))
        assertEquals("8131 Gateway Dr, Argyle, TX", AddressQuery.normalize("8131 Gateway Dr, Suite 500, Argyle, TX 76226-1234"))
        assertEquals("100 Main St, Denton", AddressQuery.normalize("100 Main St #4B, Denton"))
        assertEquals("100 Main St, Denton", AddressQuery.normalize("100 Main St Apt. 12, Denton"))
    }

    @Test fun leavesOrdinaryQueriesAlone() {
        assertEquals("Mount Scott Oklahoma", AddressQuery.normalize("Mount Scott Oklahoma"))
        assertEquals("whole foods", AddressQuery.normalize("whole foods"))
        assertEquals("Lot 5 Coffee", AddressQuery.normalize("Lot 5 Coffee"))
        assertEquals("Suite Dreams Bakery", AddressQuery.normalize("Suite Dreams Bakery"))
        assertEquals("I-35 exit 79", AddressQuery.normalize("I-35 exit 79"))
        assertEquals("Space Center Houston", AddressQuery.normalize("Space Center Houston"))
        assertEquals("Main St Floral, Denton", AddressQuery.normalize("Main St Floral, Denton"))
    }

    @Test fun houseNumber() {
        assertEquals("8131", AddressQuery.houseNumber("8131 Gateway Dr, Argyle"))
        assertEquals("12B", AddressQuery.houseNumber("12B Elm St"))
        assertNull(AddressQuery.houseNumber("Gateway Dr Argyle"))
        assertNull(AddressQuery.houseNumber("7-Eleven"))
    }

    @Test fun coordinates() {
        assertEquals(33.09962 to -97.21646, AddressQuery.coordinates("33.09962, -97.21646"))
        assertEquals(33.09962 to -97.21646, AddressQuery.coordinates("33.09962 -97.21646"))
        assertNull(AddressQuery.coordinates("8131 Gateway"))
        assertNull(AddressQuery.coordinates("95.0, 10.0"))
    }
}
