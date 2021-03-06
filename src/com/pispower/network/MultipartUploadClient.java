package com.pispower.network;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.ParseException;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.pispower.util.FileUtil;
import com.pispower.util.MD5;
import com.pispower.util.QueryString;
import com.pispower.video.upload.MutilUploadHandlerMessageParams;
import com.pispower.video.upload.UploadStatus;

public class MultipartUploadClient {

	private static final String TAG = "MultipartUploadClient";

	private static final int BUFFER_SIZE = 1024;

	private static final int PART_FILE_SIZE = 1 * 1024 * 1024; // 1M

	private Handler handler = null;

	// 请输入真实的视频文件路径
	private File file;

	// 请输入一个tmp文件夹路径
	private File tmpDir;

	private String curCatalogId;

	private final BaseClient client;

	/**
	 * 有参构造方法
	 * 
	 * @param uploadFile
	 * @param tempDir
	 * @param handler
	 */
	private MultipartUploadClient(File uploadFile, File tempDir, Handler handler) {
		super();
		this.file = uploadFile;
		this.tmpDir = tempDir;
		this.handler = handler;
		this.client = new BaseClient(this.handler);
	}

	/**
	 * 有参构造方法
	 * 
	 * @param uploadFile
	 * @param tempDir
	 * @param handler
	 */
	public MultipartUploadClient(File uploadFile, File tempDir,
			Handler handler, String curCatalogId) {
		this(uploadFile, tempDir, handler);
		this.curCatalogId = curCatalogId;
	}

	private void sendUploadStartMessage(int partNums) {
		Message message = handler.obtainMessage();
		message.what = UploadStatus.UPLOAD_START.ordinal();
		Bundle bundle = new Bundle();
		bundle.putInt(MutilUploadHandlerMessageParams.PART_NUMS, partNums);
		bundle.putString(MutilUploadHandlerMessageParams.FILE_NAME, file.getName());
		bundle.putString(MutilUploadHandlerMessageParams.FILE_SIZE, file.length() + "");
		message.setData(bundle);
		handler.sendMessage(message);
	}

	private void sendUploadingMessage(int currentValue) {
		Message message = handler.obtainMessage();
		message.what = UploadStatus.UPLOADING.ordinal();
		Bundle bundle = new Bundle();
		bundle.putLong(MutilUploadHandlerMessageParams.CURRENT_VALUE, currentValue);
		message.setData(bundle);
		handler.sendMessage(message);
	}

	private void sendUploadSuccessMessage() {
		Message message = handler.obtainMessage();
		message.what = UploadStatus.UPLOAD_SUCCESS.ordinal();
		Bundle bundle = new Bundle();
		bundle.putString(MutilUploadHandlerMessageParams.FILE_PATH, file.getAbsolutePath());
		message.setData(bundle);
		handler.sendMessage(message);
	}

	private void sendUploadFailMessage() {
		Message message = handler.obtainMessage();
		message.what = UploadStatus.UPLOAD_FAIL.ordinal();
		handler.sendMessage(message);
	}

	/**
	 * 上传文件
	 */
	public void upload() {
		try {
			splitFile();
			Log.i(TAG, "splitFile success");
			String uploadId = initMultipartUpload();
			if (uploadId == null) {
				sendUploadFailMessage();
				Log.w(TAG, "MultipartUpload failure");
			} else {
				Log.d(TAG, "initMultipartUpload success " + "uploadId is"
						+ uploadId);
				SparseArray<String> partKeysMap = uploadParts(uploadId);
				Log.d(TAG, "uploadParts success");
				completeUpload(uploadId, partKeysMap);
				Log.d(TAG, "MultipartUpload success");
				sendUploadSuccessMessage();
			}
		} catch (Exception e) {
			sendUploadFailMessage();
			Log.e(TAG, e.getMessage());
		}
		FileUtil.deleteAllFiles(tmpDir);
		return;
	}

	/**
	 * (Description)
	 * 
	 * @param partKeysMap
	 * @throws JSONException
	 * @throws ParseException
	 * @throws IOException
	 */
	private JSONObject completeUpload(final String uploadId,
			final SparseArray<String> partKeysMap) throws ParseException,
			JSONException, IOException {
		Log.d(TAG, "try to complete upload...");
		final QueryString queryString = new QueryString();
		queryString.addParam("uploadId", uploadId);
		for (int index = 0; index < partKeysMap.size(); index++) {
			final String partKey = partKeysMap.valueAt(index);
			queryString.addParam("part" + partKeysMap.keyAt(index), partKey);
		}
		queryString.addParam("catalogId", this.curCatalogId);
 		JSONObject json = client.postUrlEncodedForm("/video/multipartUpload/complete.api",
 				queryString);
		int statusCode = json.getInt("statusCode");
		if (statusCode != 0) {
			sendUploadFailMessage();
			Log.w(TAG, "statusCode is " + statusCode);
		}
		return json;
	}

	/**
	 * (Description)
	 * 
	 * @return
	 */
	private SparseArray<String> uploadParts(final String uploadId)
			throws Exception

	{
		final SparseArray<String> map = new SparseArray<String>();
		final File[] parts = tmpDir.listFiles();
		LinkedList<File> linkedParts = new LinkedList<File>(
				Arrays.asList(parts));
		final String regx = "video\\.(\\d+)\\.part";
		final Pattern p = Pattern.compile(regx);
		final Map<String, String> uploadFailpart = new HashMap<String, String>();
		int currentValue = 0;
		while (linkedParts.size() > 0) {
			final File part = linkedParts.pop();
			String md5;
			if (uploadFailpart.containsKey(part.getName())) {
				md5 = uploadFailpart.get(part.getName());
			} else {
				md5 = MD5.getFileMd5StringForAndroid(part);
			}
			final Matcher matcher = p.matcher(part.getName());
			if (matcher.find()) {
				final Integer partNum = Integer.valueOf(matcher.group(1));
				final JSONObject partJsonObject = uploadPart(uploadId, partNum,
						part);
				if (partJsonObject == null
						|| partJsonObject.getInt("statusCode") != 0
						|| !md5.equals(partJsonObject.getString("partMD5"))) {
					uploadFailpart.put(part.getName(), md5);
					linkedParts.push(part);
					Log.w(TAG, part.getName() + " part file upload failure");
				} else {
					Log.i(TAG, part.getName() + " part file upload success");
					map.put(partNum, partJsonObject.getString("partKey"));
					currentValue = currentValue + 1;
					sendUploadingMessage(currentValue);
				}
			}

		}
		return map;
	}

	/**
	 * (Description)
	 * 
	 * @param uploadId
	 * @param partNum
	 * @param part
	 * @return
	 * @throws JSONException
	 * @throws ParseException
	 * @throws IOException
	 */
	private JSONObject uploadPart(final String uploadId, final Integer partNum,
			final File part) throws ParseException, JSONException, IOException {
		Log.d(TAG, "uploading part " + partNum + " ...");
		final QueryString queryString = new QueryString();
		queryString.addParam("uploadId", uploadId);
		queryString.addParam("partNumber", String.valueOf(partNum));
		queryString.addParam("fileName", part.getAbsolutePath());
		Log.d(TAG, "filename =" + part.getAbsolutePath());
		JSONObject json = client.postFile("/video/multipartUpload/uploadPart.api",
				queryString, part);
		return json;
	}

	/**
	 * (Description)
	 * 
	 * @return
	 * @throws JSONException
	 * @throws ParseException
	 * @throws NoSuchAlgorithmException
	 */
	private String initMultipartUpload() throws ParseException, JSONException,
			IOException, NoSuchAlgorithmException {
		Log.d(TAG, "initing multipart upload....");
		final QueryString queryString = new QueryString();
		queryString.addParam("fileName", file.getName());
		String fileMD5String = null;
		fileMD5String = MD5.getFileMd5StringForAndroid(file);
		Log.d(TAG, "file md5 is " + fileMD5String);
		queryString.addParam("fileMD5", fileMD5String);
		JSONObject json = client.post("/video/multipartUpload/init.api",
				queryString);
		if (json == null) {
			return null;
		}
		if (json.getInt("statusCode") != 0) {
			return null;
		} else {
			return json.getString("uploadId");
		}

	}

	private void splitFile() throws FileNotFoundException, IOException {

		if (!tmpDir.exists() || !tmpDir.isDirectory()) {
			tmpDir.mkdirs();
		}
		final FileInputStream in = new FileInputStream(file);

		long partCount = file.length() / PART_FILE_SIZE;

		long beginIndex = 0, endIndex = 0;

		if (file.lastModified() % PART_FILE_SIZE > 0) {
			partCount++;
		}

		final byte[] buffer = new byte[BUFFER_SIZE];

		for (int i = 0; i < partCount; i++) {
			final int partNum = i + 1;
			final FileOutputStream out = new FileOutputStream(new File(tmpDir,
					"video." + partNum + ".part"));
			endIndex = endIndex + PART_FILE_SIZE;
			if (endIndex > file.length()) {
				endIndex = file.length();
			}
			while (beginIndex < endIndex) {
				byte[] bff = buffer;

				if (endIndex - beginIndex < BUFFER_SIZE) {
					bff = new byte[(int) (endIndex - beginIndex)];
				}
				final int readCount = in.read(bff);
				beginIndex += readCount;
				out.write(bff);
			}
			out.close();
		}
		in.close();
		sendUploadStartMessage((int) partCount);
	}

}
