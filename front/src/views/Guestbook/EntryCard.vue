<template>
  <div class="entry-card" :class="{ reply: !isRoot }">
    <header class="entry-head"><div class="identity"><span class="avatar">{{ entry.username?.slice(0, 1).toUpperCase() }}</span><div><strong>{{ entry.username }}</strong><small>{{ formatDate(entry.createdAt) }}</small></div></div><n-button v-if="entry.canDelete" text type="error" size="small" @click="$emit('delete', entry)">删除</n-button></header>
    <p class="entry-content">{{ entry.content }}</p>
    <footer class="entry-actions"><n-button text size="small" :disabled="entry.authorId === auth.user?.id" @click="toggleLike"><template #icon><n-icon><HeartOutline :class="{ liked: entry.likedByMe }" /></n-icon></template>{{ entry.likeCount || 0 }}</n-button><n-button v-if="isRoot" text size="small" @click="$emit('toggle-replies')"><template #icon><n-icon><ChatbubbleEllipsesOutline /></n-icon></template>{{ expanded ? '收起回复' : `${entry.replyCount || 0} 条回复` }}</n-button></footer>
    <section v-if="isRoot && expanded" class="replies"><n-spin :show="loadingReplies"><EntryCard v-for="reply in replies" :key="reply.id" :entry="reply" @toggle-like="$emit('toggle-like', $event)" @delete="$emit('delete', $event)" @request-login="$emit('request-login')" /><n-button v-if="replyPage < replyTotalPages" block text type="primary" @click="$emit('load-more-replies')">加载更多回复</n-button></n-spin><div class="reply-composer"><n-input :value="replyDraft" type="textarea" maxlength="500" show-count :autosize="{ minRows: 2, maxRows: 5 }" placeholder="写下回复…" @update:value="$emit('update:reply-draft', $event)" /><div><n-button size="small" type="primary" :loading="postingReply" @click="submit">回复</n-button></div></div></section>
  </div>
</template>

<script setup>
import { NButton, NIcon, NInput, NSpin } from 'naive-ui'
import { ChatbubbleEllipsesOutline, HeartOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
const props = defineProps({ entry:{type:Object,required:true}, isRoot:Boolean, expanded:Boolean, replyDraft:String, replies:{type:Array,default:()=>[]}, replyPage:{type:Number,default:1}, replyTotalPages:{type:Number,default:1}, loadingReplies:Boolean, postingReply:Boolean })
const emit = defineEmits(['toggle-like','delete','request-login','toggle-replies','update:reply-draft','submit-reply','load-more-replies'])
const auth = useAuthStore()
function submit(){ if(!auth.isLoggedIn) emit('request-login'); else emit('submit-reply') }
function toggleLike(){ if(!auth.isLoggedIn) emit('request-login'); else if(props.entry.authorId !== auth.user?.id) emit('toggle-like', props.entry) }
function formatDate(value){ const date=new Date(value); return Number.isNaN(date.getTime())?'':new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'short'}).format(date) }
</script>

<style scoped lang="scss">
.entry-card { padding:18px; }.entry-card.reply { margin-top:10px; padding:14px; border-left:2px solid #c8bcaa; background:#f5f1e8; }.entry-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }.identity { display:flex; align-items:center; gap:10px; min-width:0; }.avatar { width:34px; height:34px; display:grid; place-items:center; flex:0 0 auto; border-radius:50%; background:#b83126; color:#fffaf1; font-family:Georgia,serif; font-weight:700; }.identity div { display:grid; min-width:0; }.identity strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.identity small { color:#8a8175; font-size:11px; }.entry-content { margin:14px 0 12px; color:#302e29; white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.7; }.entry-actions { display:flex; align-items:center; gap:12px; }.liked { color:#b83126; fill:currentColor; }.replies { display:grid; gap:10px; margin:16px -4px -4px 26px; padding:14px 0 0 14px; border-left:1px solid #ddd3c5; }.reply-composer { display:grid; gap:8px; margin-top:4px; }.reply-composer>div { display:flex; justify-content:flex-end; } @media(max-width:560px){.entry-card{padding:14px}.replies{margin-left:12px;padding-left:10px}.entry-actions :deep(.n-button){min-height:38px}.reply-composer :deep(.n-button){min-height:40px;min-width:72px} }
</style>
