<template>
  <div class="not-found">
    <div class="code">404</div>
    <h1>这里没有找到页面</h1>
    <p>地址可能已变更，或者你没有访问权限。</p>
    <el-button type="primary" @click="$router.push('/')">返回工作台</el-button>
  </div>
</template>

<style scoped>
.not-found { min-height: 100vh; display: grid; place-content: center; text-align: center; background: #f4f7f8; padding: 24px; }
.code { font-size: 96px; line-height: 1; font-weight: 900; color: #0f766e; letter-spacing: -.08em; }
h1 { margin: 18px 0 8px; }
p { color: #687388; margin-bottom: 22px; }
</style>
