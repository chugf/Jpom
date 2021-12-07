package io.jpom.controller.node.ssh;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.jiangzeyin.common.DefaultSystemLog;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import cn.jiangzeyin.controller.multipart.MultipartFileBuilder;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.Type;
import io.jpom.model.data.NodeModel;
import io.jpom.model.data.SshModel;
import io.jpom.model.system.AgentAutoUser;
import io.jpom.plugin.ClassFeature;
import io.jpom.plugin.Feature;
import io.jpom.plugin.MethodFeature;
import io.jpom.service.node.ssh.SshService;
import io.jpom.system.ConfigBean;
import io.jpom.system.ExtConfigBean;
import io.jpom.system.ServerConfigBean;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileUrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ssh 安装插件端
 *
 * @author bwcx_jzy
 * @date 2019/8/17
 */
@Controller
@RequestMapping(value = "node/ssh")
@Feature(cls = ClassFeature.SSH)
public class SshInstallAgentController extends BaseServerController {

	private final SshService sshService;

	public SshInstallAgentController(SshService sshService) {
		this.sshService = sshService;
	}

	@RequestMapping(value = "installAgentSubmit.json", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	@Feature(method = MethodFeature.EXECUTE)
	public String installAgentSubmit(@ValidatorItem(value = ValidatorRule.NOT_BLANK) String id,
									 @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "节点数据") String nodeData,
									 @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "安装路径") String path) throws Exception {
		// 判断输入的节点信息
		Object object = getNodeModel(nodeData);
		if (object instanceof JsonMessage) {
			return object.toString();
		}
		NodeModel nodeModel = (NodeModel) object;
		//
		SshModel sshModel = sshService.getByKey(id, false);
		Objects.requireNonNull(sshModel, "没有找到对应ssh");
		//
		String tempFilePath = ServerConfigBean.getInstance().getUserTempPath().getAbsolutePath();
		MultipartFileBuilder cert = createMultipart().addFieldName("file").setSavePath(tempFilePath);
		String filePath = cert.save();
		//
		File outFle = FileUtil.file(tempFilePath, Type.Agent.name() + "_" + IdUtil.fastSimpleUUID());
		try {
			try (ZipFile zipFile = new ZipFile(filePath)) {
				// 判断文件是否正确
				ZipEntry sh = zipFile.getEntry(Type.Agent.name() + ".sh");
				ZipEntry lib = zipFile.getEntry("lib" + StrUtil.SLASH);
				if (sh == null || null == lib || !lib.isDirectory()) {
					return JsonMessage.getString(405, "不是 Jpom 插件包");
				}
				ZipUtil.unzip(zipFile, outFle);
			}
			// 获取上传的tag
			File shFile = FileUtil.file(outFle, Type.Agent.name() + ".sh");
			List<String> lines = FileUtil.readLines(shFile, CharsetUtil.CHARSET_UTF_8);
			String tag = null;
			for (String line : lines) {
				line = line.trim();
				if (StrUtil.startWith(line, "Tag=\"") && StrUtil.endWith(line, "\"")) {
					tag = line.substring(5, line.length() - 1);
					break;
				}
			}
			Assert.hasText(tag, "管理命令中不存在tag");
			//  读取授权信息
			File configFile = FileUtil.file(outFle, ExtConfigBean.FILE_NAME);
			if (configFile.exists()) {
				YamlPropertySourceLoader yamlPropertySourceLoader = new YamlPropertySourceLoader();
				List<PropertySource<?>> extConfig = yamlPropertySourceLoader.load(ExtConfigBean.FILE_NAME, new FileUrlResource(configFile.getAbsolutePath()));
				Assert.notEmpty(extConfig, "没有加载到配置信息,或者配置信息为空");
				PropertySource<?> propertySource = extConfig.get(0);
				Object user = propertySource.getProperty(ConfigBean.AUTHORIZE_USER_KEY);
				nodeModel.setLoginName(Convert.toStr(user, ""));
				//
				Object pwd = propertySource.getProperty(ConfigBean.AUTHORIZE_AUTHORIZE_KEY);
				nodeModel.setLoginPwd(Convert.toStr(pwd, ""));
			}
			// 查询远程是否运行

			Assert.state(!sshService.checkSshRun(sshModel, tag), "对应服务器中已经存在 Jpom 插件端,不需要再次安装啦");

			// 上传文件到服务器
			sshService.uploadDir(sshModel, path, outFle);
			//
			String shPtah = FileUtil.normalize(path + StrUtil.SLASH + Type.Agent.name() + ".sh");
			String chmod = getParameter("chmod");
			if (StrUtil.isEmpty(chmod)) {
				chmod = StrUtil.EMPTY;
			} else {
				chmod = StrUtil.format("{} {} && ", chmod, shPtah);
			}
			String command = StrUtil.format("{}sh {} start upgrade", chmod, shPtah);
			String result = sshService.exec(sshModel, command);
			// 休眠 5 秒, 尝试 5 次
			int waitCount = getParameterInt("waitCount", 5);
			waitCount = Math.max(waitCount, 5);
			//int time = 3;
			while (--waitCount >= 0) {
				//DefaultSystemLog.getLog().debug("there is left {} / 3 times try to get authorize info", waitCount);
				Thread.sleep(5 * 1000);
				if (StrUtil.isEmpty(nodeModel.getLoginName()) || StrUtil.isEmpty(nodeModel.getLoginPwd())) {
					String error = this.getAuthorize(sshModel, nodeModel, path);
					// 获取授权成功就不需要继续循环了
					if (error == null) {
						waitCount = -1;
					}
					// 获取授权失败且尝试次数用完
					if (error != null && waitCount == 0) {
						return error;
					}
				}
			}
			nodeModel.setOpenStatus(1);
			// 绑定关系
			//nodeModel.setSshId(sshModel.getId());
			nodeService.insert(nodeModel);
			//
			return JsonMessage.getString(200, "操作成功:" + result);
		} finally {
			// 清理资源
			FileUtil.del(filePath);
			FileUtil.del(outFle);
		}
	}

	private String getAuthorize(SshModel sshModel, NodeModel nodeModel, String path) {
		File saveFile = null;
		try {
			String tempFilePath = ServerConfigBean.getInstance().getUserTempPath().getAbsolutePath();
			//  获取远程的授权信息
			String normalize = FileUtil.normalize(StrUtil.format("{}/{}/{}", path, ConfigBean.DATA, ConfigBean.AUTHORIZE));
			saveFile = FileUtil.file(tempFilePath, IdUtil.fastSimpleUUID() + ConfigBean.AUTHORIZE);
			sshService.download(sshModel, normalize, saveFile);
			//
			String json = FileUtil.readString(saveFile, CharsetUtil.CHARSET_UTF_8);
			AgentAutoUser autoUser = JSONObject.parseObject(json, AgentAutoUser.class);
			nodeModel.setLoginPwd(autoUser.getAgentPwd());
			nodeModel.setLoginName(autoUser.getAgentName());
		} catch (Exception e) {
			DefaultSystemLog.getLog().error("拉取授权信息失败", e);
			return JsonMessage.getString(500, "获取授权信息失败,请检查对应的插件端运行状态", e.getMessage());
		} finally {
			FileUtil.del(saveFile);
		}
		return null;
	}

	private Object getNodeModel(String data) {
		NodeModel nodeModel = JSONObject.toJavaObject(JSONObject.parseObject(data), NodeModel.class);
		Assert.hasText(nodeModel.getId(), "节点id错误");

		Assert.hasText(nodeModel.getName(), "输入节点名称");

		Assert.hasText(nodeModel.getUrl(), "请输入节点地址");

		return nodeModel;
	}

}
