package chat.rocket.android.chatroom.adapter

import android.view.View
import chat.rocket.android.chatroom.viewmodel.VideoAttachmentViewModel
import chat.rocket.android.player.PlayerActivity
import chat.rocket.android.util.extensions.setVisible
import kotlinx.android.synthetic.main.message_attachment.view.*

class VideoAttachmentViewHolder(itemView: View, listener: ActionsListener)
    : BaseViewHolder<VideoAttachmentViewModel>(itemView, listener) {

    init {
        with(itemView) {
            image_attachment.setVisible(false)
            audio_video_attachment.setVisible(true)
            setupActionMenu(attachment_container)
            setupActionMenu(audio_video_attachment)
        }
    }

    override fun bindViews(data: VideoAttachmentViewModel) {
        with(itemView) {
            file_name.text = data.attachmentTitle
            audio_video_attachment.setOnClickListener { view ->
                data.attachmentUrl.let { url ->
                    PlayerActivity.play(view.context, url)
                }
            }
        }
    }
}