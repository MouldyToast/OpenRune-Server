package org.rsmod.api.net.rsprot.handlers

import dev.openrune.ServerCacheManager
import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.resumed.ResumePauseButton
import org.rsmod.annotations.InternalApi
import org.rsmod.api.net.rsprot.player.InterfaceEvents
import org.rsmod.api.player.input.ResumePauseButtonInput
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.IfPauseButton
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.ui.Component
import org.rsmod.game.ui.UserInterface

class ResumePauseButtonHandler
@Inject
constructor(private val eventBus: EventBus, private val protectedAccess: ProtectedAccessLauncher) :
    MessageHandler<ResumePauseButton> {
    private val ResumePauseButton.asComponent: Component
        get() = Component(interfaceId, componentId)

    @OptIn(InternalApi::class)
    override fun handle(player: Player, message: ResumePauseButton) {
        val componentType = ServerCacheManager.fromComponent(message.asComponent.packed)
        val interfaceType = ServerCacheManager.fromInterface(message.asComponent.packed)
        val userInterface = UserInterface(interfaceType)

        val pauseEnabled =
            InterfaceEvents.isEnabled(player.ui, componentType, message.sub, IfEvent.PauseButton)
        if (!pauseEnabled) {
            return
        }

        val input = ResumePauseButtonInput(RSCM.getReverseMapping(RSCMType.COMPONENT,componentType.packed), message.sub)

        // Dialogue-style flows suspend on ResumePauseButtonInput and are resumed below (closing
        // the paused sub, e.g. a chat page). When no script is awaiting the input, the click is
        // instead published as an IfPauseButton event WITHOUT closing anything — this is how
        // pause-button-driven interfaces that stay open between clicks (e.g. ToA party screens)
        // receive their buttons. Mirrors If3ButtonHandler's modal protection semantics.
        // Components with no IfPauseButton subscriber keep the LEGACY behavior: a stray pause
        // click (nothing awaiting, nothing consuming) still closes the paused page as it did
        // before the event existed.
        val awaitingPauseInput = player.activeCoroutine?.isAwaiting(ResumePauseButtonInput::class) == true
        if (!awaitingPauseInput) {
            if (player.isModalButtonProtected) {
                return
            }
            val opened =
                player.ui.modals.getComponent(userInterface)
                    ?: player.ui.overlays.getComponent(userInterface)
                    ?: return
            if (eventBus.contains(IfPauseButton::class.java, componentType.packed)) {
                val event = IfPauseButton(componentType, message.sub)
                protectedAccess.launchLenient(player) { eventBus.publish(this, event) }
            } else {
                player.ui.queueClose(opened)
                player.resumeActiveCoroutine(input)
            }
            return
        }

        val modal = player.ui.modals.getComponent(userInterface)
        if (modal != null) {
            player.ui.queueClose(modal)
            player.resumeActiveCoroutine(input)
            return
        }

        val overlay = player.ui.overlays.getComponent(userInterface)
        if (overlay != null) {
            player.ui.queueClose(overlay)
            player.resumeActiveCoroutine(input)
            return
        }
    }
}
