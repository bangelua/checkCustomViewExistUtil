import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class main {
	private static final boolean DEBUG = false;

	/**
	 * check whether custom view in layout xml exists actually, or which will cause ClassNotFoundException in runtime.
	 * @param args apk or aar file path
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		if (args == null || args.length < 1) {
			System.err.println("缺少参数: apk or aar file");
			return;
		}
		String apkPath = args[0];
		if (!apkPath.startsWith(File.separator)) {
			String currentPath = System.getProperty("user.dir");
			apkPath = currentPath + File.separator + apkPath;
		}
		File apkFile = new File(apkPath);

		// 临时工作目录
		String tempPath = "/tmp/check_view_exists_" + System.currentTimeMillis();

		// 保存 apktool.jar 到临时目录
		String apkToolJarFile = saveApkTool(new File(tempPath));

		// apktool 反编译出资源文件
		System.out.println("正在通过 apktool 反编译出资源文件\n");
		File decompileOutDir = new File(tempPath, "de_out");
		String DECOMPILE_RESOURCE_CMD = "java -jar " + apkToolJarFile + " d " + apkPath + " -o "
				+ decompileOutDir.getAbsolutePath();
		Runtime runtime = Runtime.getRuntime();
		// System.out.println(DECOMPILE_RESOURCE_CMD);
		Process result = runtime.exec(DECOMPILE_RESOURCE_CMD);
		int exitValue = result.waitFor();
		if (exitValue != 0) {
			System.err.println(inputSteamToString(result.getErrorStream()));
			return;
		}

		// 收集所有布局文件
		List<File> layoutXmlFiles = new ArrayList<File>();
		getAllXmlFiles(decompileOutDir, layoutXmlFiles);

		// 查出布局中包含的所有自定义 view class, key is custom view class, value is layout
		// xmls which contains this class.
		HashMap<String, HashSet<String>> customViewClassSet = new HashMap<>();
		for (int i = 0; i < layoutXmlFiles.size(); i++) {
			// System.out.println("list " + layoutXmlFiles.get(i));
			File xmlFile = layoutXmlFiles.get(i);
			HashSet<String> viewClassOfXml = parseCustomViewOfXml(xmlFile);
			Iterator<String> iter = viewClassOfXml.iterator();
			while (iter.hasNext()) {
				String viewClass = iter.next();
				HashSet<String> xmlSet = customViewClassSet.get(viewClass);
				if (xmlSet == null) {
					xmlSet = new HashSet<>();
				}
				xmlSet.add(xmlFile.getAbsolutePath());
				customViewClassSet.put(viewClass, xmlSet);
			}
		}

		// 解压 apk or aar 文件, 找到 classes.dex 文件
		System.out.println("开始解压 " + apkFile.getName() + " 文件中的 classes.dex\n");
		UnZipFile.unZipFiles(apkFile, tempPath);

		System.out.println("正在解析出 classes.dex 中所有的 class\n");
		Set<String> classesOfDex = new HashSet<String>();

		File[] dexClassFiles = new File(tempPath).listFiles(new FileFilter() {

			@Override
			public boolean accept(File pathname) {
				return pathname.getName().endsWith(".dex");
			}
		});
		for (File dexClassFile : dexClassFiles) {
			DexFile dexFile = DexFileFactory.loadDexFile(dexClassFile, Opcodes.forApi(19));
			if (DEBUG)
				System.out.println("found " + dexFile.getClasses().size() + " classes in " + dexClassFile.getName());
			for (ClassDef classDef : dexFile.getClasses()) {
				String rawClassName = classDef.getType();
				String formatClass = formatRawClass(rawClassName);
				if (DEBUG)
					System.out.println("formatClass " + formatClass);
				classesOfDex.add(formatClass);
			}
		}

		int failedCount = 0;
		Set<String> set = customViewClassSet.keySet();
		Iterator<String> iter = set.iterator();
		while (iter.hasNext()) {
			String customViewClass = iter.next();
			if (!classesOfDex.contains(customViewClass)) {
				failedCount++;
				HashSet<String> xmlSet = customViewClassSet.get(customViewClass);
				int count = xmlSet.size();
				Iterator<String> xmlIter = xmlSet.iterator();
				StringBuilder xmlsBuilder = new StringBuilder("");
				int index = 0;
				while (xmlIter.hasNext()) {
					String xmlName = new File(xmlIter.next()).getName();
					index++;
					if (index == 1) {
						xmlsBuilder.append(xmlName);
					} else if (index == count) {
						xmlsBuilder.append(" and ").append(xmlName);
					} else {
						xmlsBuilder.append(", ").append(xmlName);
					}
				}
				System.err.println("Not found defined custom View class: " + customViewClass + " into " + xmlsBuilder);
			}
		}
		if (failedCount > 0) {
			System.err.println("校验失败: 请检查以上错误日志");
		} else {
			System.out.println("校验通过:布局文件中的自定义 View 对应的 Class 都存在");
		}
		deleteDir(new File(tempPath));
	}

	public static void deleteDir(File file) {
		if (file.isDirectory()) {
			for (File f : file.listFiles())
				deleteDir(f);
		}
		file.delete();
	}

	/**
	 * convert "Lcom/bk/base/commonview/MyTitleBar;" to
	 * "com.bk.base.commonview.MyTitleBar"
	 * 
	 * @param rawClass
	 * @return
	 */
	private static String formatRawClass(String rawClass) {
		if (rawClass.startsWith("L")) {
			rawClass = rawClass.substring(1);
		}
		if (rawClass.endsWith(";")) {
			rawClass = rawClass.substring(0, rawClass.length() - 1);
		}
		rawClass = rawClass.replaceAll("/", ".").replace("$", ".");
		return rawClass.substring(0, rawClass.length());
	}

	private static HashSet<String> parseCustomViewOfXml(File xmlFile) {
		HashSet<String> labelsString = new HashSet<>();
		try {
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dbBuilder = dbFactory.newDocumentBuilder();
			Document doc = dbBuilder.parse(xmlFile.getAbsolutePath());
			NodeList nodeList = doc.getChildNodes();
			parseNodeList(nodeList, labelsString);
		} catch (ParserConfigurationException | SAXException | IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("error occur when parseCustomViewOfXml: " + e.getMessage());
		}
		return labelsString;
	}

	private static void parseNodeList(NodeList nodeList, Set<String> viewList) {
		for (int i = 0; nodeList != null && i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i);
			String nodeName = node.getNodeName();
			// 假定自定义 view 包名写全的, 包含'.',且不以 android 开头
			if (nodeName.contains(".") && !nodeName.startsWith("android")) {
				// System.out.println(" " + nodeName);
				viewList.add(nodeName);
			}
			parseNodeList(node.getChildNodes(), viewList);
		}
	}

	private static boolean isLayoutXmlFile(File file) {
		if (!file.getName().endsWith(".xml")) {
			return false;
		}
		if (!file.getParentFile().getName().contains("layout")) {
			return false;
		}
		return true;
	}

	private static void getAllXmlFiles(File file, List<File> list) {
		if (file.isDirectory()) {
			File[] subFiles = file.listFiles();
			if (subFiles == null)
				return;
			for (int i = 0; i < subFiles.length; i++) {
				getAllXmlFiles(subFiles[i], list);
			}

		} else if (isLayoutXmlFile(file)) {
			list.add(file);
		}
	}

	private static String inputSteamToString(InputStream inputStream) throws IOException {
		byte[] bytes = new byte[0];
		bytes = new byte[inputStream.available()];
		inputStream.read(bytes);
		String str = new String(bytes);
		return str;
	}

	private static String saveApkTool(File destDir) throws IOException {
		if (destDir == null) {
			System.err.println("save ApkTool's destDir is null");
		}
		if (!destDir.exists()) {
			destDir.mkdirs();
		}
		final String APKTOOL = "apktool_2.3.4.jar";
		File outFile = new File(destDir, APKTOOL);
		FileOutputStream oos = new FileOutputStream(outFile);
		InputStream is = main.class.getResourceAsStream("/resource/" + APKTOOL);
		byte[] buffer = new byte[1024 * 4];
		int length;
		while ((length = is.read(buffer)) != -1) {
			oos.write(buffer, 0, length);
		}
		oos.close();
		is.close();
		return outFile.getAbsolutePath();
	}
}
