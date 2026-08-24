plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.attr)
    implementation(projects.api.instances)
    implementation(projects.api.npc)
    implementation(projects.api.route)
    implementation(projects.api.death)
    implementation(projects.content.other.consumables)
}
