package dev.compose.hotreload.sample

fun propertyDataValues(value: PropertyData): String {
    val (title, amount) = value
    return "$title|$amount|$value"
}

/** Real getters, constructors and generated data-class calls; no reflective value injection. */
fun inspectProperties(vm: PropertyViewModel, business: PropertyBusiness,
                      delegates: PropertyDelegates, data: PropertyData): List<String> {
    val freshVm = PropertyViewModel()
    val freshBusiness = PropertyBusiness()
    val freshDelegates = PropertyDelegates()
    val freshData = PropertyData()
    val copied = data.copy()
    check(copied !== data && copied == data && copied.hashCode() == data.hashCode())
    check(copied != freshData)
    return listOf(
        "VM old: ${vm.values()}", "VM new: ${freshVm.values()}",
        "Biz old: ${business.values()}", "Biz new: ${freshBusiness.values()}",
        "Delegate old: ${delegates.readValue()}|${delegates.addedValue()}",
        "Delegate new: ${freshDelegates.readValue()}|${freshDelegates.addedValue()}",
        "Lazy old: ${delegates.lazyValues()}", "Lazy new: ${freshDelegates.lazyValues()}",
        "Data old: ${propertyDataValues(data)}", "Data new: ${propertyDataValues(freshData)}",
        "Data copy: ${propertyDataValues(copied)}", "Data equality: true")
}
